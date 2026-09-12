# Item Shower — Rendern angeschlossen

Gebaut auf **deinem 1.3.0-Baum**, nicht auf meinem alten Stand. Version steht
jetzt auf 1.4.0.

## Neu

| Datei | Zweck |
|---|---|
| `mixin/ItemEntityRendererMixin` | Items am Boden — der Totem-Fall |
| `mixin/ItemEntityRenderStateMixin` + `mixin/ItemIdHolder` | tragen die Item-Id durch |
| `mixin/ItemInHandRendererMixin` | Item in der Hand |
| `mixin/GuiItemScaleMixin` | Hotbar und Inventar |
| `ui/Scale.translate` | fehlte noch, die Hotbar braucht sie |
| `ItemSizes.keyFor` | eine Stelle für die Schlüsselbildung |

## Dein Fix war der richtige, und er betraf mehr als du gesehen hast

`ItemStack.getDescriptionId()` existiert auf 26.2 nicht; die Id sitzt am Item,
nicht am Stack. Dein `stack.getItem().getDescriptionId()` ist korrekt — dieselbe
Zeile stand aber auch in allen drei Mixins, die ich noch nicht abgeschickt
hatte. Die wären mit exakt demselben Fehler durchgefallen.

Damit das nicht wieder auseinanderläuft, gibt es jetzt `ItemSizes.keyFor(stack)`
und alle vier Stellen benutzen sie. Ein Schlüssel, der an zwei Orten anders
berechnet wird, ist eine Einstellung, die stillschweigend nie greift — und das
wäre schwer zu finden gewesen, weil nichts abstürzt.

## Warum der Boden-Fall zwei Mixins braucht

`ItemEntityRenderState` hält ein gebackenes Modell, keinen ItemStack. Zum
Zeichenzeitpunkt ist also nicht mehr feststellbar, welches Item das war. Die Id
wird deshalb in `extractRenderState` abgegriffen — dem letzten Punkt, an dem die
Entity noch erreichbar ist — und über ein Interface am Render-State
mitgetragen.

## Verifiziert

Aus deinen Jar-Dumps, nicht geraten:

- `ItemEntityRenderer.submit(ItemEntityRenderState, PoseStack, SubmitNodeCollector, CameraRenderState)`
- `ItemEntityRenderer.extractRenderState(ItemEntity, ItemEntityRenderState, float)`
- `ItemInHandRenderer.renderItem(LivingEntity, ItemStack, ItemDisplayContext, PoseStack, SubmitNodeCollector, int)`
- `PoseStack.scale(FFF)V` und `translate(FFF)V` — beide als Methodref im
  Konstantenpool von ItemInHandRenderer
- `GuiGraphicsExtractor.item(LivingEntity, Level, ItemStack, int, int, int)` —
  durch den bestehenden GuiItemInvoker bewiesen

Das Pushen sitzt auf HEAD, das Poppen auf RETURN statt am Ende: `submit` kehrt
früh zurück, wenn der Stack leer ist, und dieser eine Pfad hätte sonst die
Matrix gepusht gelassen — alles danach im Frame hätte die Skalierung geerbt.

`required: false` und `defaultRequire: 0` stehen weiterhin in der mixins.json.
Falls ein Injection-Punkt doch nicht passt, wird er übersprungen statt das Spiel
beim Start abzuschießen; die Größe wirkt dann einfach nicht.

---

# HUD-Editor: warum Skalieren gar nicht ging

Es lag nicht daran, dass die Ecke zu klein war. Sie war **unerreichbar**.

Der Körper-Griff deckt die ganze Fläche des Elements ab, einschließlich der
Ecke, und wurde dem Screen zuerst hinzugefügt. Ein `Screen` gibt den Klick an
das erste Kind, das ihn annimmt — also hat immer der Körper gewonnen und der
Eckgriff lag darunter, unerreichbar. Mein Kommentar behauptete das Gegenteil
("wird nach dem Körper hinzugefügt, also gewinnt er"), und das war schlicht
falsch herum gedacht.

## Drei Wege statt einem

- **Modus „Resize"** — oben im Editor umschalten, dann skaliert ein Klick auf
  eine beliebige Stelle des Elements. Kein Zielen nötig. Das ist der Weg, der
  ohne Erklärung funktioniert.
- **Mausrad** über einem Element, jederzeit und in jedem Modus.
- **Eckgriff**, jetzt 14 statt 8 Pixel, dauerhaft sichtbar statt erst beim
  Überfahren. Ein Bedienelement, das erscheint sobald man schon darüber ist,
  kann man nicht anvisieren.

## Neu dazu

**Ausrichten mit Fanglinien.** Beim Verschieben rasten Kanten auf
Bildschirmrand, Bildschirmmitte und die Kanten aller anderen Elemente ein, mit
5 Pixeln Toleranz und einer Linie, die zeigt worauf. Elemente nach Augenmaß
auszurichten ist der langsamste Teil am Einrichten, und zwei Pixel daneben
sieht man im Spiel, aber nicht beim Platzieren.

**Sperren.** Modus „Lock", dann schaltet ein Klick das Element fest. Sobald
etwas genau richtig sitzt, geht es beim nächsten Besuch im Editor um ein
anderes Element — und das eingestellte im Vorbeigehen um zwei Pixel zu
verschieben ist das Ärgerlichste, was der Editor anrichten kann.

**„Reset sizes".** Der Rückweg aus einem Fehler: wer ein Element auf dreifache
Größe zieht, bekommt es sonst kaum wieder zu fassen.

**Neues Modul „Day & Time".** Der Ingame-Tag, die Uhrzeit und wie lange es
noch hell ist. Das Clock-Element zeigt die echte Uhrzeit — also „wie lange
spiele ich schon". Das hier beantwortet die andere Frage: ob sich der Weg nach
Hause noch lohnt oder man weitergraben kann.

## Was ich nicht benutzt habe

`AbstractWidget.setWidth` ist im Projekt nirgends belegt. Statt es zu riskieren
werden die Griffe nach dem Skalieren neu aufgebaut — `rebuildWidgets` ist im
Hauptmenü bereits bewiesen. `getDayTime` läuft aus demselben Grund über
Reflection: bei einem Fehlgriff steht ein Strich auf dem Bildschirm statt eines
fehlgeschlagenen Builds.

---

# PvP: Inventaranzeige und Vorrat

## Inventory

Die drei Lagerreihen dauerhaft auf dem Bildschirm. Kein Avatar, kein
Handwerksfeld, keine Rüstungsslots — der Avatar und das Handwerksfeld sind in
diesem Zusammenhang Dekoration, und die Rüstung hat bereits ein eigenes
Element.

Worum es eigentlich geht: Im Kampf bedeutet Inventar öffnen stehenbleiben mit
verdecktem Bildschirm, und die zwei Fragen, für die man es öffnet — wie viele
Totems noch, ist noch ein Gapple da — sind in einem Blick beantwortet und
kosten eine Runde, wenn man dafür anhält.

Optionen: Hotbar-Reihe dazu (standardmäßig aus, sie ist ohnehin auf dem
Schirm), Zweithand-Slot, Stapelgrößen, Haltbarkeitsbalken, leere Felder
ausblenden.

Die Stapelgröße zeichne ich selbst. Der Invoker gibt Zugriff auf `item` und
`itemBar`; die Zahl gehört zu einem Decorations-Aufruf, der nicht dabei ist.

## Supplies

Totems, Gapples, Pearls und optional Pfeile als reine Zahlen. Das Inventar
beantwortet dasselbe, aber es beantwortet es durch Lesen — das hier durch
Hinsehen, und das ist ein Unterschied, während jemand auf dich einschlägt. Eine
Zahl, die von 3 auf 2 gesprungen ist, bemerkt man ohne den Blick vom Kampf zu
nehmen; ein Raster aus Symbolen nicht.

Bei 1 wird die Zahl gelb, bei 0 rot. Zur Neige gehen und leer sein sind
verschiedene Probleme, und das erste ist das, was man rechtzeitig sehen will.

Die Liste ist bewusst kurz. Das sind die Items, deren Anzahl ändert was man als
Nächstes tut; Blöcke und Schwerter mit aufzunehmen würde aus dem Blick wieder
ein Lesen machen.

## Eine Vorsichtsmaßnahme

`ItemStack.getCount()` läuft über eine einmalig aufgelöste Reflection statt
über einen direkten Aufruf. Sie ist mit ziemlicher Sicherheit noch da — aber
`getDescriptionId()` war es auch, bis es einen Build gekostet hat. Der Umweg
kostet nichts Messbares und macht aus derselben Überraschung eine Null auf dem
Bildschirm statt eines Fehlschlags.

---

# Drei weitere PvP-Module

## Nearby Players

Wer in der Nähe ist, wie weit weg, und wie gut verbunden. Die Entfernung ist
im Kampf der wichtige Teil und genau der, den das Spiel nie sagt — Namensschilder
verblassen, verschwinden hinter Blöcken und nennen keine Zahl. „Bin ich schon
in Reichweite" beantwortet man sonst durch Zuschlagen.

Der Ping steht dabei, weil er ändert *wie* jemand gegen dich spielt, nicht wie
gut: Wer bei 300 ms ist, trifft aus weiterer Entfernung als sein Modell
vermuten lässt. Das vor dem Kampf zu wissen ist mehr wert, als es währenddessen
zu lernen.

Farbe nach Reichweite: rot in Schwertreichweite, gelb in Sprintweite, grau
darüber.

## Health

Herzen sind bei voller Leiste lesbar und im Kampf nicht. Ein halbes Herz
entscheidet, ob ein weiterer Treffer überlebt wird, und Herzen zu zählen
während sie zittern und blinken ist genau das, was in dem Moment niemand kann.
Eine Zahl zittert nicht.

Absorption steht getrennt daneben statt aufaddiert, weil sie sich anders
verhält: sie regeneriert nicht und verschwindet auf einmal. Eine Summe würde
den Teil verstecken, der gleich weg ist.

## Durability

Das Rüstungselement beobachtet, was du trägst. Das hier beobachtet das, was den
Schaden macht — also das, was mitten im Schlag bricht. Der Vanilla-Balken
erscheint bei einem Fünftel und ist vier Pixel hoch: eine Warnung, die zu spät
und zu leise kommt.

Zahlen statt Balken, weil „sechzig Schläge übrig" und „acht Schläge übrig"
verschiedene Entscheidungen sind und beide wie ein kurzer oranger Strich
aussehen.

## Abgesichert statt geraten

`getHealth`, `getAbsorptionAmount` und `getFoodData` werden sonst nirgends im
Mod aufgerufen, also beweist sie nichts — sie laufen über Reflection und
zeigen im Zweifel einen Strich.

Beim Spielernamen sind es drei Versuche hintereinander: `getGameProfile`,
dann `Entity.getName().getString()`, dann das Profil aus der Tab-Liste. Zuletzt
ein Fragezeichen. `getDescriptionId` sah genauso sicher aus, bis es einen Build
gekostet hat.

`getMaxDamage`, `getDamageValue`, `getItemBySlot` und `mc.level.players()` sind
dagegen durch bestehende Module bewiesen und werden direkt aufgerufen.

---

# Fairness-Durchgang: was entfernt und was festgenagelt wurde

Ziel: ein Client, der auf keinem Server ein Bannrisiko ist. Ich habe nicht nur
die zwei angesprochenen Module geprüft, sondern alle.

## Entfernt: Nearby Players

Das war ein Radar. Es zeigte Spieler und deren Entfernung auch dann, wenn du
sie nicht sehen kannst — hinter Wänden, im Dunkeln, hinter dir. Dass die Daten
ohnehin im Client liegen, ändert nichts: das ist genau das Argument, mit dem
jedes ESP verteidigt wird.

Mein Fehler. Ich habe „nützlich im PvP" gedacht und nicht zu Ende, ob es fair
ist.

## Beschnitten: Space Players

Die globale Zahl bleibt, der „hier in der Welt"-Teil ist weg. Der war die
interessantere Zahl und genau deshalb das Problem: er meldete Spieler, die du
nicht sehen kannst. Eine Anzahl ist weniger als eine Position, aber es ist
weiterhin Information über Leute, die das Spiel dir nicht zeigt.

## Festgenagelt: Hitbox

Hier lag der Fall anders als gedacht, und schlimmer.

`hideBehindWalls` war eine Einstellung mit Standardwert **aus**. Der Client kam
also mit Boxen durch Wände heraus — und der verwendete Render-Typ `debugQuads`
hat keinen Tiefentest, „durch Wände" war also wörtlich zu nehmen. Das ist ESP,
egal wie es beschriftet ist.

Sichtlinie und „unsichtbare Entities überspringen" sind jetzt Konstanten statt
Einstellungen. Damit zeigt das Modul, was vanillas eigenes F3+B zeigt: Kästen
um Dinge, die du ohnehin siehst. Nichts wird enthüllt, was das Spiel verborgen
hat.

## Geprüft und unbedenklich

- **Health, Durability, Supplies, Inventory** — ausschließlich deine eigenen
  Daten, die das Spiel dir ohnehin zeigt. Nur anders dargestellt.
- **Ping, Connection** — nur die eigene UUID.
- **Server Info** — die Spielerzahl aus der Tab-Liste, also das, was der Server
  selbst veröffentlicht.
- **Aim** — deine eigene Blickrichtung, wie F3.
- **Zoom, CPS, Keystrokes, Wavey Cape** — auf allen großen Servern erlaubt.
- **Item Shower** — ändert nur die Größe der Darstellung, deckt nichts auf, was
  nicht sichtbar wäre. Ein Totem in einem Haufen ist auch vorher da gewesen.

---

# Die Versionsanzeige war gelogen

`SpaceClient.VERSION` stand fest auf `"0.1.0"` — seit dem allerersten Stand,
über jede Version hinweg. Das Menü zeigte also unabhängig vom tatsächlichen
Build immer dieselbe Zahl.

Das ist mehr als ein Schönheitsfehler: Es macht die einzige Frage, die sich
lohnt wenn etwas fehlt — „läuft überhaupt der neue Build?" — von innerhalb des
Spiels unbeantwortbar. Genau diese Frage steht gerade im Raum.

Die Version wird jetzt aus den Metadaten der Jar gelesen, also aus dem, was in
`gradle.properties` steht. Der Footer im Menü und die Zeile
`Space Client X ready` im Log sagen ab sofort die Wahrheit.

## Zu „Health und Durability existieren nicht"

Beide sind im ZIP und in `ModuleManager` registriert, das habe ich geprüft.
`ModuleCategories` hat einen Fallback, unbekannte Ids landen unter HUD — sie
können also nicht durch den Filter fallen.

Zusammen mit „Item Shower ändert nichts" ergibt das ein stimmiges Bild: Beides
ist genau das Verhalten von **1.3.0**. In dem Stand fehlen die Mixins und die
sechs neuen Module. Es sieht nach einer alten Jar aus, nicht nach einem Fehler
im Code.

Nach dem nächsten Build steht im Menü unten rechts **v1.4.0**. Steht dort
weiterhin etwas anderes, lief die alte Jar. Steht dort 1.4.0 und die Module
fehlen trotzdem, ist es ein echter Fehler und ich brauche das `latest.log`.

---

# Was der Log verraten hat

`spaceclient 1.4.0` war geladen, keine Mixin-Fehler — und trotzdem stand da
`Space Client 0.1.0 ready`. Das bestätigt den fest verdrahteten
Versions-String, der jetzt aus der Jar gelesen wird.

Es zeigt aber auch einen Fehler von mir: Ich habe mehrere verschiedene Stände
alle als **1.4.0** ausgeliefert. Du konntest dadurch nicht unterscheiden, welcher
davon läuft, und ich auch nicht. Diese Version ist **1.5.0**, und ab jetzt
bekommt jeder Stand eine eigene Nummer.

## Warum Item Shower nichts tat, ohne einen Fehler zu melden

`defaultRequire: 0` in der mixins.json ist Absicht — ein verschobenes Ziel soll
übersprungen werden statt das Spiel beim Start abzuschießen. Der Preis: ein
Hook, der nie greift, beschwert sich auch nie. Genau das passt zu deiner
Beobachtung.

Auf der Diagnostics-Seite steht deshalb jetzt eine Zeile **Item scaling** mit
`ground / hand / hotbar`, jeweils `ok` oder `not yet`. Jeder Hook meldet sich
beim ersten Mal, dass er läuft.

`not yet` heißt nicht kaputt: Der Boden-Hook meldet sich erst, wenn ein Item am
Boden gezeichnet wurde. Wirf also etwas hin und schau nochmal. Bleibt einer nach
dem Test auf `not yet`, ist das die Antwort, die bisher gefehlt hat.

# Hintergründe

Drei Fotos dazu: **Nebula** (NGC 6357), **Black hole**, **Galaxy** (M83). Die
bestehenden vier bleiben unverändert und stehen weiter vorne in der Reihenfolge,
damit man nicht durch drei Bilder blättern muss, um zu einem schlichten
Hintergrund zu kommen.

Umschalten unter **Appearance → Background**. Die Namen stehen dort jetzt
lesbar ("Black hole" statt "BLACK_HOLE"); gespeichert wird weiterhin der alte
Bezeichner, damit bestehende Configs gültig bleiben.

Über jedes Foto legt sich ein dunkler Schleier. Das sind helle Bilder und das
Menü ist weißer Text — ohne den säße die Schrift auf dem, was zufällig
dahinter liegt, und die Hälfte davon wäre weg.

## Zur Auflösung, und warum nicht 4K

Ich habe beides erzeugt und gemessen:

| | Nebula | Black hole | Galaxy | gesamt |
|---|---|---|---|---|
| 3840×2160 | 9,0 MB | 3,5 MB | 12,6 MB | **25,1 MB** |
| 1920×1080 | 3,1 MB | 1,4 MB | 4,1 MB | **8,6 MB** |

Ausschlaggebend ist aber die Quelle. Die Originale sind 1000×749, 1024×576 und
2000×1301. Auf 4K hochgerechnet entsteht kein einziges Detail dazu — es wird
dieselbe Bildinformation auf mehr Pixel verteilt. Zwei der drei Bilder sind
selbst bei 1080p schon vergrößert.

Dazu kommt der Speicher: Eine 3840×2160-Textur belegt rund 33 MB Videospeicher,
1920×1080 etwa 8 MB. Für einen Menühintergrund, der ohnehin nur auf
Bildschirmgröße gezeichnet wird.

Deshalb liegen 1080p bei. Wenn du 4K trotzdem willst, sag Bescheid — die
Dateien sind fertig, das ist ein Austausch von drei PNGs.

---

# 1.5.1 — Blackscreen behoben

```
gg.spaceclient.mixin.ItemIdHolder is in a defined mixin package
gg.spaceclient.mixin.* owned by spaceclient.mixins.json
and cannot be referenced directly
```

Mein Fehler. Alles unter `gg.spaceclient.mixin` gehört der Mixin-Konfiguration
und wird beim Laden transformiert. Eine gewöhnliche Klasse dort kann von
normalem Code nicht geladen werden — der Versuch lässt die Transformation
scheitern, das reißt das Ressourcenladen mit, und das Ergebnis ist ein
schwarzer Bildschirm.

`ItemIdHolder` und `ItemScaleReport` sind keine Mixins. Sie liegen jetzt unter
`gg.spaceclient.access`.

Dass `GuiItemInvoker` im selben Paket funktioniert, hatte mich in die Irre
geführt: Das *ist* ein Mixin und steht in der `mixins.json`. Genau dieser
Unterschied ist mir durchgerutscht — ich habe im Kopf abgehakt "liegt neben
etwas, das geht, also geht es auch".

Als Absicherung habe ich gegengeprüft, dass jede Klasse im Mixin-Paket auch in
der `mixins.json` steht und umgekehrt. Beide Listen decken sich jetzt genau.
Wenn dort künftig etwas Neues hinzukommt, ist das der Test, der diesen Fehler
sofort findet.

Interessant am Log ist noch, dass das Spiel nicht abgestürzt ist — es hat
stattdessen gemeldet, dass es alle Ressourcenpakete entfernt. Deshalb kein
Crash-Report, nur schwarz.

---

# 1.6.0 — Schriftart und zählende Zahlen

## Schrift

**Inter** liegt jetzt bei und ersetzt Minecrafts Pixelschrift überall: Menüs,
Chat, Schilder, Bücher, Itemnamen, Hauptmenü und die Screens dieses Mods. Sie
kommt der Schrift aus deinem Screenshot sehr nahe und steht unter der SIL Open
Font License — die darf mitgeliefert werden, die Lizenzdatei liegt daneben im
Jar.

Umgesetzt über `assets/minecraft/font/default.json`. Die Reihenfolge dort ist
kein Zufall: Spätere Einträge gewinnen, deshalb steht die TrueType-Schrift
zuletzt und die vanilla-Einträge davor. Das ist wichtiger als es klingt — Inter
hat keine chinesischen, japanischen oder koreanischen Zeichen, und ohne den
vanilla-Eintrag als Rückfall würden die im Chat einfach verschwinden.

**Das kann ich nicht testen, und zwei Zahlen werden vermutlich nachjustiert
werden müssen.** In der `default.json` stehen `size` (10.5) und `shift`
([0, -1]). Sitzt der Text zu hoch oder zu tief, ist es `shift`; wirkt er neben
der Oberfläche zu groß oder zu klein, ist es `size`. Alles andere dort würde ich
in Ruhe lassen.

Und eine Nebenwirkung, die du kennen solltest: Das betrifft wirklich *alles*.
Der Vanilla-Look ist damit weg, auch auf Schildern und in Büchern. Es gibt
keinen Schalter dafür — eine Schriftart lässt sich im laufenden Spiel nicht
umhängen. Wenn du es doch abschaltbar willst, wäre der Weg ein eigenes
Ressourcenpaket statt fester Assets; sag Bescheid.

## Zählende Zahlen

Neu: `ui/Rolling`. Angewendet auf **FPS**, **Ping** und **Speicher**.

Der Grund ist nicht Zierde. FPS ändert sich jeden Frame, und eine roh
gedruckte Zahl ist dadurch ein Flackern statt einer Zahl — das Auge folgt der
Bewegung und landet nie auf einem Wert. Das Einlaufenlassen beruhigt die
letzten Stellen und macht die Zahl *während* sie sich ändert lesbarer, nicht
weniger.

Zwei Dinge tut es bewusst nicht. Es animiert nie den ersten Wert, sonst zählt
jeder Menüaufruf von null hoch wie ein Ladebalken. Und bei großen Sprüngen
springt es: Ein Ping von 40 auf 900 ist ein Ereignis, und eine halbe Sekunde
sanft dorthin zu gleiten würde genau den Moment verstecken, der auffallen soll.

Beim Ping musste dafür `cachedText` weichen. Das baut den Text auf einem Timer
neu, was die Zahl in Stufen springen ließe und die Animation aufhöbe — die
braucht jeden Frame einen Wert. Der Messwert selbst ändert sich weiterhin nur,
wenn der Server etwas Neues sagt.

Beim Speicher läuft nur der belegte Teil, nicht das Maximum. Das ist für die
Laufzeit des Prozesses konstant, da wäre eine Animation eine Animation von
nichts.

---

# 1.7.0 — Glas und Bewegung

## Zuerst das Ehrliche über „Liquid Glass"

Echtes Frosted Glass heißt: das Bild hinter der Fläche auslesen und
verwischen. Das braucht einen Shader über dem Framebuffer, und so etwas würde
ich hier auf gut Glück schreiben und beim nächsten Versionswechsel wieder
verlieren. Das habe ich nicht gemacht.

Was stattdessen da ist, trägt den Look mit den Mitteln, die sicher sind:
abgerundete Kanten, eine helle Linie dort wo Licht auf die Oberkante fällt,
eine dunkle darunter, und ein Körper der oben heller ist als unten. Diese eine
helle Zeile macht mehr aus als der ganze Verlauf.

Neu: `ui/Glass`. Jedes HUD-Element mit Hintergrund bekommt jetzt so eine
Platte — dieselbe Farbe und Deckkraft wie vorher, nur nicht mehr als flaches
Rechteck. Ebenso die Modulzeilen im Menü: unter dem Zeiger hebt sich die Zeile
an, statt nur die Farbe zu wechseln.

Die Rundung entsteht dadurch, dass jede Zeile am Rand ein Stück eingerückt
wird. Bei den kleinen Radien einer Oberfläche ist das von einer echten Kurve
nicht zu unterscheiden und kostet eine Handvoll Füllungen.

## Jedes Modul reagiert auf etwas anderes

Neu: `ui/Pulse` — ein kurzes Aufleuchten bei Veränderung, das hält und dann
weich ausläuft. Sofort zu verblassen wirkt wie ein Flackern statt wie eine
Meldung.

Entscheidend ist, dass jedes Modul auf *seine* Sache reagiert und nicht alle
auf dasselbe:

- **Supplies** blitzt nur nach unten. Ein Totem aufzuheben ist keine Nachricht;
  eines zu verlieren ist das, was du bemerken musst während dich etwas anderes
  beschäftigt.
- **Health** blitzt bei Schaden, nicht bei Regeneration. Der Moment, in dem du
  am wenigsten in der Lage bist, eine Zahl zu lesen — also kommt die Zahl dir
  entgegen.
- **Twitch** leuchtet nach *oben*. Die eine Zahl hier, bei der der Anstieg das
  Ereignis ist.
- **FPS, Ping, Speicher** laufen weiterhin in ihre Werte ein statt zu springen.

## Aurora

Ein neuer Hintergrund, und der einzige der sich wirklich bewegt. Drei breite
Farbbänder gleiten mit unterschiedlichen Perioden übereinander, jedes aus
vielen schmalen durchscheinenden Spalten — überlappende Transparenz ist das,
was die Ränder weich macht, wenn kein Blur zur Verfügung steht. Die Perioden
sind teilerfremd gewählt, damit sich das Muster nirgends sichtbar wiederholt.

Bewusst langsam. Ein Hintergrund, der Aufmerksamkeit auf sich zieht, ist ein
Hintergrund, der mit dem Menü davor konkurriert.

Das erste Band nimmt deine Akzentfarbe auf, die beiden anderen setzen kühl und
warm dagegen.

---

# 1.7.1 — drei Fehler aus 1.7.0

## Die Schrift wurde still verworfen

Inter wird nur als **Variable Font** veröffentlicht (`fvar`/`gvar` in der
Datei). Minecrafts Font-Loader liest die nicht — er lässt den Provider
kommentarlos fallen, und genau deshalb hat sich nichts geändert.

Jetzt liegt **Barlow** bei: statisch, mit gewöhnlichen `glyf`-Umrissen, die der
Loader erwartet. Nebenbei 101 KB statt 856. Ebenfalls SIL Open Font License,
Lizenzdatei liegt daneben.

Das hätte mir auffallen müssen. Ich habe die Datei heruntergeladen, ihre Größe
geprüft und nicht, was für ein Format sie ist.

## Der graue Strich

Mein Fehler, und ein lehrreicher. Ich habe die Glanzkante fest auf 55 % Weiß
gesetzt — unabhängig davon, wie deckend die Fläche darunter ist. Bei einer
dunklen, halbtransparenten HUD-Platte verschwindet der Körper im dunklen
Hintergrund, und übrig bleibt eine helle Linie, unter der nichts liegt.

Ein Glanzlicht kann nicht heller sein als die Oberfläche, auf der es liegen
soll. Alle Kanten leiten sich jetzt aus der Deckkraft der Platte ab und sind
gedeckelt: bei der Standard-HUD-Platte 38 statt 70, bei einer sehr
transparenten 14 statt 26.

Zusätzlich wird der Verlauf nicht mehr über die Transparenz gemacht, sondern
über die Farbe. Nach oben hin transparenter zu werden war der erste Versuch und
war falsch — auf dunklem Grund löste sich die Platte damit oben einfach auf.

## Hintergrund nur im Appearance-Menü

Das war eine bewusste Entscheidung von mir, und eine schlechte. Ich hatte im
Hauptmenü `Backdrop.draw` durch einen einfachen Schleier ersetzt, weil ein
bildschirmfüllendes Sternenfeld hinter einem Fenster wie ein Blackout wirkte.

Damit habe ich das falsche Problem gelöst: Das Ergebnis war eine Einstellung,
die sichtbar nirgends etwas tut außer auf dem Screen, auf dem man sie ändert.
Das Hauptmenü zeichnet jetzt denselben Hintergrund wie alle anderen Screens,
mit etwas zusätzlichem Schatten darüber, damit sich das Panel auch vor einem
hellen Foto noch abhebt.

---

# 1.8.0 — Open Sans und ein echter Odometer

## Schrift

**Open Sans**, statisch, 144 KB. Nicht aus `google/fonts` — dort liegt die
Familie inzwischen nur noch als Variable Font, und genau daran ist Inter
gescheitert. Diese Datei kommt aus `googlefonts/opensans` und hat gewöhnliche
`glyf`-Umrisse, die Minecrafts Loader liest. SIL Open Font License, Lizenz
liegt daneben.

Ich prüfe die Schrift jetzt vor dem Einbauen auf `fvar` in der Tabellenliste.
Das ist der Test, der bei Inter gefehlt hat.

## Der Zähler war der falsche Effekt

Du hast recht, und der Framer-Baustein zeigt genau, woran es lag.

Mein erster Versuch hat den **Wert** animiert: von 40 auf 47 zählte er durch
41, 42, 43. Das ist etwas anderes als das, was du wolltest, und schlechter — es
erfindet Messwerte, die es nie gab, und lässt eine stabile Bildrate unruhig
aussehen.

Der Framer-Effekt animiert die **Ziffern**. Wenn aus 47 eine 52 wird, rutscht
die 4 nach oben hinaus und die 5 kommt von unten nach; die 7 und die 2 folgen
einen Moment später. Die Zahl ist immer nur 47 oder 52 — was sich bewegt, ist
die Type.

Neu: `ui/Odometer`. Der Versatz läuft von rechts nach links, so wie ein
mechanisches Zählwerk arbeitet: Das letzte Rad dreht zuerst und nimmt das
nächste mit. Verglichen wird ebenfalls von rechts, damit bei 99 → 100 nur die
Ziffern rollen, die sich wirklich geändert haben.

Ein Detail, das die Umsetzung geprägt hat: Ohne Beschneidungsrechteck bleibt
eine Ziffer, die eine volle Zeilenhöhe wandert, auf dem Weg lesbar — und zwei
lesbare Ziffern in einem Feld sind schlimmer als eine kurze Bewegung. Deshalb
sind es 60 % der Zeilenhöhe plus Ausblenden.

Angewendet auf **FPS** und **Ping**. Beide bekommen ihre Werte etwa einmal pro
Sekunde, es rollt also einmal pro Messung.

**Speicher** behält das Einlaufen, und das ist Absicht: Der Wert driftet
laufend und fällt dann ab, wenn der Collector läuft. Jede Einheit einzeln zu
rollen wäre ein Dauerflimmern. Hier ist Zählen der richtige Effekt, dort
Rollen.

---

# 1.8.1 — Zählwerk auch bei Supplies, Health und Session

## Session

Der sauberste Fall von allen: Ein Sitzungstimer ändert sich exakt einmal pro
Sekunde und nur in der letzten Ziffer. Die Sekunden klappen um wie auf einer
Anzeigetafel, die Minuten bewegen sich erst, wenn sie sollen.

## Supplies

Ein eigenes Zählwerk pro Zeile, abgelegt unter dem Namen der Zeile statt unter
ihrer Position. Das ist wichtiger, als es klingt: Schaltet man Pfeile dazu,
rutschen alle Zeilen darunter eine Stelle weiter — ein an die Position
gebundenes Zählwerk würde dann vom alten Wert dieser Zeile auf den neuen
rollen, als hätte sich der Bestand geändert.

Das Aufblitzen bleibt zusätzlich erhalten. Ein Totem, das von 2 auf 1 fällt,
rollt und leuchtet gleichzeitig.

## Health

Rollt die ganze Anzeige, nicht nur die Lebenszahl. Da nur veränderte Zeichen
sich bewegen, dreht sich beim Regenerieren die letzte Ziffer und der Rest steht
still; ein echter Treffer ändert mehrere Stellen auf einmal und die Anzeige
kippt komplett um. Genau der Unterschied ist der, den man sehen will.

Die Farbe wird vor dem Rollen bestimmt, damit der Schadens-Blitz im selben
Frame beginnt wie die Bewegung, statt einen Frame hinterherzuhinken.

---

# 1.8.2 — Buildfehler in SessionModule

```
SessionModule.java:35: error: annotation interface not applicable
to this kind of declaration
    @Override
```

Beim Einfügen des Zählwerks habe ich Feld und Doku-Kommentar **zwischen** das
vorhandene `@Override` und die `render`-Methode geschoben. Die Annotation hing
danach am Feld.

Meine eigene Aufräum-Prüfung hat das übersehen, weil sie nach `@Override`
direkt vor dem Feld gesucht hat — dazwischen lag aber der Kommentar. Ein Muster,
das nur den unmittelbar nächsten Zeilenanfang betrachtet, findet genau diesen
Fall nicht.

Der Prüfer sucht jetzt die nächste *bedeutungstragende* Zeile und überspringt
dabei Leerzeilen und weitere Annotationen. Über das ganze Projekt laufen
gelassen: keine weitere Fundstelle.

## Was ich dabei über meine eigene Prüfung gelernt habe

Der zweite Durchlauf meldete zunächst siebzehn Dateien als kaputt, darunter
welche, die ich nie angefasst habe. Ursache war die Reihenfolge im Prüfer: Er
entfernte Zeichenliterale vor den Kommentaren, und ein Apostroph in Prosa
("the sun's position") wurde als Literalbeginn gelesen und verschluckte den
halben Quelltext.

Nach der Korrektur blieben vier Dateien übrig — ebenfalls falsch positiv:
`SessionManager` benutzt Java-Textblöcke, die das Muster nicht kennt, und alle
vier sind seit deiner laufenden 1.3.0 unverändert.

Die Lehre für mich: Diese Klammerprüfung findet grobe Schnitzer, ist aber kein
Compiler-Ersatz. Ich habe mich hier zu sehr darauf verlassen und deshalb eine
Annotation an der falschen Stelle stehen lassen.
