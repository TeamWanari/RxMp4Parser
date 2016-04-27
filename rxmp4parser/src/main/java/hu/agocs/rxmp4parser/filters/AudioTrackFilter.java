package hu.agocs.rxmp4parser.filters;

import org.mp4parser.muxer.Track;

import rx.functions.Func1;

public class AudioTrackFilter implements Func1<Track, Boolean> {

    private static final String SOUND_TRACK = "soun";

    @Override
    public Boolean call(Track track) {
        return SOUND_TRACK.equals(track.getHandler());
    }
}


