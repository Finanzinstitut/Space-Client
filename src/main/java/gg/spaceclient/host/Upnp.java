package gg.spaceclient.host;

import gg.spaceclient.SpaceClient;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Asks the router to forward a port, using UPnP.
 *
 * Written out by hand rather than pulled in as a library, because the whole
 * protocol is three steps and a dependency would mean a new Gradle entry and a
 * new repository for something this small:
 *
 *   1. shout on the local network and see which device answers as a gateway
 *   2. fetch that device's description and find the service that maps ports
 *   3. send it a SOAP request to add the mapping
 *
 * Every step can fail for ordinary reasons - no UPnP, switched off in the
 * router, or an internet connection that has no public address to forward at
 * all. None of those are errors worth throwing; they just mean the answer is
 * no, and the reason is kept so the menu can say which one it was.
 */
public final class Upnp {

    private static final String SSDP_HOST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    /** The two service names that can map ports; routers have one or the other. */
    private static final String[] SERVICES = {
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    private static volatile String controlUrl = "";
    private static volatile String serviceType = "";
    private static volatile String localAddress = "";
    private static volatile String reason = "not tried yet";

    public static String reason() { return reason; }

    /**
     * Opens the port on the router.
     *
     * @return the public address, or empty when the router would not do it
     */
    public static String map(int port) {
        try {
            if (!discover()) return "";

            String body = "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">"
                    + "<NewRemoteHost></NewRemoteHost>"
                    + "<NewExternalPort>" + port + "</NewExternalPort>"
                    + "<NewProtocol>TCP</NewProtocol>"
                    + "<NewInternalPort>" + port + "</NewInternalPort>"
                    + "<NewInternalClient>" + localAddress + "</NewInternalClient>"
                    + "<NewEnabled>1</NewEnabled>"
                    + "<NewPortMappingDescription>Space Client hosted world"
                    + "</NewPortMappingDescription>"
                    + "<NewLeaseDuration>0</NewLeaseDuration>"
                    + "</u:AddPortMapping>";

            String answer = soap("AddPortMapping", body);
            if (answer == null) {
                // The router said no. The most common cause by far is that the
                // port is already mapped to a different machine.
                reason = "the router refused the port mapping";
                return "";
            }

            String external = externalAddress();
            if (external.isEmpty()) {
                reason = "port mapped, but the router would not say its public address";
                return "";
            }

            reason = "ok";
            return external;

        } catch (Throwable t) {
            reason = "port mapping failed: " + t.getMessage();
            return "";
        }
    }

    /** Takes the mapping back down. Best effort - a stale mapping is untidy, not harmful. */
    public static void unmap(int port) {
        try {
            if (controlUrl.isEmpty()) return;

            String body = "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">"
                    + "<NewRemoteHost></NewRemoteHost>"
                    + "<NewExternalPort>" + port + "</NewExternalPort>"
                    + "<NewProtocol>TCP</NewProtocol>"
                    + "</u:DeletePortMapping>";

            soap("DeletePortMapping", body);

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not remove the port mapping: {}", t.getMessage());
        }
    }

    // ---------------- step one: find the gateway ----------------

    private static boolean discover() throws Exception {
        if (!controlUrl.isEmpty()) return true;

        boolean anyAnswer = false;

        // Asked from every network address this machine has, not just the
        // default one. A PC with a VPN, a virtual machine adapter or two
        // network cards will happily send the search out of the interface the
        // router is not on, and then hear nothing back.
        for (InetAddress source : localAddresses()) {
            if (search(source)) return true;
            if (!lastAnswerEmpty) anyAnswer = true;
        }

        reason = anyAnswer
                ? "a device answered, but it cannot forward ports"
                : "no router answered - UPnP is probably switched off";
        return false;
    }

    /** Set while searching, so discover can tell silence from a useless answer. */
    private static boolean lastAnswerEmpty = true;

    /** Sends the search from one local address and waits for a gateway. */
    private static boolean search(InetAddress source) {
        lastAnswerEmpty = true;

        String message = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_HOST + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"
                + "\r\n";

        // Bound to this address on purpose, so the search leaves through the
        // matching interface and the router replies to somewhere we listen
        try (DatagramSocket socket = new DatagramSocket(0, source)) {
            socket.setSoTimeout(1200);
            socket.setBroadcast(true);

            byte[] out = message.getBytes("UTF-8");

            // Sent more than once: SSDP is UDP, and a single lost packet
            // would otherwise look exactly like a router with UPnP disabled
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(out, out.length,
                        InetAddress.getByName(SSDP_HOST), SSDP_PORT));
            }

            long deadline = System.currentTimeMillis() + 3000;
            byte[] buffer = new byte[4096];

            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (Throwable timeout) {
                    continue;
                }

                lastAnswerEmpty = false;

                String response = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                String location = header(response, "LOCATION");
                if (location.isEmpty()) continue;

                if (readDescription(location)) {
                    // The address the router should forward to is the one this
                    // socket is bound to - it is by definition on the router's
                    // own network
                    localAddress = source.getHostAddress();
                    return true;
                }
            }

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Search from {} failed: {}",
                    source.getHostAddress(), t.getMessage());
        }
        return false;
    }

    /** Every ordinary IPv4 address this machine has. */
    private static List<InetAddress> localAddresses() {
        List<InetAddress> found = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {

                if (!network.isUp() || network.isLoopback()) continue;

                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        found.add(address);
                    }
                }
            }
        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not list network interfaces: {}", t.getMessage());
        }
        return found;
    }

    /** Pulls the control URL for the port mapping service out of the device description. */
    private static boolean readDescription(String location) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(location))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            String xml = response.body();

            for (String service : SERVICES) {
                int at = xml.indexOf(service);
                if (at < 0) continue;

                String path = tag(xml.substring(at), "controlURL");
                if (path.isEmpty()) continue;

                URI base = URI.create(location);
                controlUrl = path.startsWith("http")
                        ? path
                        : base.getScheme() + "://" + base.getAuthority()
                                + (path.startsWith("/") ? path : "/" + path);
                serviceType = service;
                return true;
            }

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not read the router description: {}", t.getMessage());
        }
        return false;
    }

    // ---------------- step three: talk to it ----------------

    private static String soap(String action, String body) {
        try {
            String envelope = "<?xml version=\"1.0\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>" + body + "</s:Body></s:Envelope>";

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(controlUrl))
                            .header("Content-Type", "text/xml; charset=\"utf-8\"")
                            .header("SOAPAction", "\"" + serviceType + "#" + action + "\"")
                            .timeout(Duration.ofSeconds(6))
                            .POST(HttpRequest.BodyPublishers.ofString(envelope))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200 ? response.body() : null;

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Router request {} failed: {}", action, t.getMessage());
            return null;
        }
    }

    /** Asks the router for the address the outside world sees. */
    private static String externalAddress() {
        String answer = soap("GetExternalIPAddress",
                "<u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\">"
                        + "</u:GetExternalIPAddress>");
        if (answer == null) return "";

        String address = tag(answer, "NewExternalIPAddress");

        // A private address here means the router itself is behind another one,
        // which is what most mobile and cable connections do. Forwarding a port
        // on the inner router achieves nothing in that case, and saying so
        // beats handing out an address that cannot work.
        if (address.startsWith("10.") || address.startsWith("192.168.")
                || address.startsWith("172.") || address.startsWith("100.")) {
            reason = "your connection sits behind the provider's router (CGNAT), "
                    + "so a forwarded port cannot be reached";
            return "";
        }

        return address;
    }

    // ---------------- tiny parsers ----------------

    private static String header(String response, String name) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    /** Reads the first <name>...</name>, ignoring any namespace prefix. */
    private static String tag(String xml, String name) {
        int open = xml.indexOf("<" + name + ">");
        if (open < 0) return "";
        int start = open + name.length() + 2;
        int close = xml.indexOf("</", start);
        return close < 0 ? "" : xml.substring(start, close).trim();
    }

    private Upnp() {}
}
