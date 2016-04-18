package hu.agocs.rxmp4parser;

import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.container.mp4.MovieCreator;
import org.mp4parser.muxer.tracks.AppendTrack;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.FuncN;

public class RxMp4Parser {

    private static final String SOUND_TRACK = "soun";
    private static final String VIDEO_TRACK = "vide";

    public static Observable<Movie> from(final String inputPath) {
        return Observable.create(new Observable.OnSubscribe<Movie>() {
            @Override
            public void call(Subscriber<? super Movie> subscriber) {
                if (new File(inputPath).exists()) {
                    try {
                        subscriber.onNext(MovieCreator.build(inputPath));
                    } catch (IOException e) {
                        subscriber.onError(e);
                    }
                } else {
                    subscriber.onError(new FileNotFoundException(inputPath));
                }
                subscriber.onCompleted();
            }
        });
    }

    public static Observable<Movie> from(final File inputFile) {
        return Observable.create(new Observable.OnSubscribe<Movie>() {
            @Override
            public void call(Subscriber<? super Movie> subscriber) {
                if (inputFile.exists()) {
                    try {
                        subscriber.onNext(MovieCreator.build(inputFile.getAbsolutePath()));
                    } catch (IOException e) {
                        subscriber.onError(e);
                    }
                } else {
                    subscriber.onError(new FileNotFoundException(inputFile.getAbsolutePath()));
                }
                subscriber.onCompleted();
            }
        });
    }

    public static Observable<AppendTrack> appendTracks(List<? extends Track> tracks) {
        return Observable.just(tracks)
                .flatMap(new Func1<List<? extends Track>, Observable<AppendTrack>>() {
                    @Override
                    public Observable<AppendTrack> call(List<? extends Track> tracks) {
                        try {
                            return Observable.just(new AppendTrack(tracks.toArray(new Track[tracks.size()])));
                        } catch (IOException e) {
                            return Observable.error(e);
                        }
                    }
                });
    }

    public static Observable<Movie> mux(final Iterable<? extends Track> tracks) {
        return Observable.create(new Observable.OnSubscribe<Movie>() {
            @Override
            public void call(Subscriber<? super Movie> subscriber) {
                subscriber.onNext(Utils.mux(tracks));
                subscriber.onCompleted();
            }
        });
    }

    public static Observable<Track> extractTrackWithHandler(final Movie movie, final String handler) {
        return Observable.from(movie.getTracks())
                .filter(new Func1<Track, Boolean>() {
                    @Override
                    public Boolean call(Track track) {
                        return handler.equals(track.getHandler());
                    }
                })
                .firstOrDefault(null);
    }

    public static Observable<Track> extractAudioTrack(final Movie movie) {
        return extractTrackWithHandler(movie, SOUND_TRACK);
    }

    public static Observable<Track> extractVideoTrack(final Movie movie) {
        return extractTrackWithHandler(movie, VIDEO_TRACK);
    }

    public static Observable<Movie> concatenate(Iterable<? extends Observable<Movie>> input) {
        return Observable.zip(input, new FuncN<Iterable<Movie>>() {
            @Override
            public Iterable<Movie> call(Object... args) {
                List<Movie> movies = new ArrayList<Movie>();
                for (Object movie : args) {
                    movies.add((Movie) movie);
                }
                return movies;
            }
        }).flatMap(new Func1<Iterable<Movie>, Observable<Movie>>() {
            @Override
            public Observable<Movie> call(Iterable<Movie> movies) {
                return Observable.zip(
                        Observable.from(movies)
                                .flatMap(new Func1<Movie, Observable<Track>>() {
                                    @Override
                                    public Observable<Track> call(Movie movie) {
                                        return extractAudioTrack(movie);
                                    }
                                })
                                .toList()
                                .flatMap(new Func1<List<Track>, Observable<AppendTrack>>() {
                                    @Override
                                    public Observable<AppendTrack> call(List<Track> tracks) {
                                        return appendTracks(tracks);
                                    }
                                }),
                        Observable.from(movies)
                                .flatMap(new Func1<Movie, Observable<Track>>() {
                                    @Override
                                    public Observable<Track> call(Movie movie) {
                                        return extractVideoTrack(movie);
                                    }
                                })
                                .toList()
                                .flatMap(new Func1<List<Track>, Observable<AppendTrack>>() {
                                    @Override
                                    public Observable<AppendTrack> call(List<Track> tracks) {
                                        return appendTracks(tracks);
                                    }
                                }),
                        new Func2<AppendTrack, AppendTrack, Movie>() {
                            @Override
                            public Movie call(AppendTrack audioTrack, AppendTrack videoTrack) {
                                return Utils.mux(audioTrack, videoTrack);
                            }
                        }
                );
            }
        });
    }


}
