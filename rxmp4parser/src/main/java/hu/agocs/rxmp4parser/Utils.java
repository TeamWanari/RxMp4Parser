package hu.agocs.rxmp4parser;

import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;

public class Utils {

    public static Movie mux(Iterable<? extends Track> tracks) {
        Movie movie = new Movie();
        for (Track track : tracks) {
            movie.addTrack(track);
        }
        return movie;
    }

    public static Movie mux(Track... tracks) {
        Movie movie = new Movie();
        for (Track track : tracks) {
            movie.addTrack(track);
        }
        return movie;
    }

}
