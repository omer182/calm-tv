package io.github.omer182.calmtv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A hand-picked wake-up playlist; one song is looked up on YouTube at alarm time. */
final class MorningSongs {
    static final String[] SONGS = {
            "The Beatles Here Comes The Sun",
            "Bill Withers Lovely Day",
            "Katrina and the Waves Walking on Sunshine",
            "Pharrell Williams Happy",
            "Bob Marley Three Little Birds",
            "Jack Johnson Banana Pancakes",
            "Justin Timberlake Can't Stop the Feeling",
            "Earth Wind and Fire September",
            "Louis Armstrong What a Wonderful World",
            "Nina Simone Feeling Good",
            "Coldplay A Sky Full of Stars",
            "Bruno Mars Count on Me",
            "Vance Joy Riptide",
            "Avicii Wake Me Up",
            "The Lumineers Ho Hey",
            "Jason Mraz I'm Yours",
            "Israel Kamakawiwo'ole Somewhere Over the Rainbow",
            "Natasha Bedingfield Unwritten",
            "Of Monsters and Men Little Talks",
            "Phillip Phillips Home",
            "Florence and the Machine Dog Days Are Over",
            "Harry Styles Sunflower Vol 6",
            "Cat Stevens Morning Has Broken",
            "Owl City Good Time",
            "Andy Grammer Good to Be Alive",
            "Edward Sharpe and the Magnetic Zeros Home",
            "Kool and the Gang Celebration",
            "Bobby McFerrin Don't Worry Be Happy",
    };

    private static final Pattern VIDEO_ID =
            Pattern.compile("videoId(?:\\\\x22|\")\\s*:\\s*(?:\\\\x22|\")([\\w-]{11})");

    private MorningSongs() {
    }

    static String pick() {
        return SONGS[new Random().nextInt(SONGS.length)];
    }

    /** First YouTube result for the song's official video; blocking, null on failure. */
    static String findVideoId(String song) {
        HttpURLConnection conn = null;
        try {
            String q = URLEncoder.encode(song + " official video", "UTF-8");
            conn = (HttpURLConnection) new URL("https://www.youtube.com/results?search_query=" + q).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            // A desktop browser identity gets plain JSON in the page instead of \x22-escaped data.
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            StringBuilder body = new StringBuilder();
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            for (String line; (line = in.readLine()) != null; ) {
                body.append(line);
                Matcher m = VIDEO_ID.matcher(body);
                if (m.find()) {
                    in.close();
                    return m.group(1);
                }
            }
            in.close();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
