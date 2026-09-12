package gg.spaceclient.music;

/** What is currently playing, as far as the desktop app reveals. */
public record NowPlaying(String source, String artist, String title, boolean playing) {

    public static final NowPlaying NOTHING = new NowPlaying("", "", "", false);

    public boolean isEmpty() {
        return title.isEmpty();
    }

    /** "Artist - Title", or just the title when no artist was parsed. */
    public String display() {
        return artist.isEmpty() ? title : artist + " - " + title;
    }
}
