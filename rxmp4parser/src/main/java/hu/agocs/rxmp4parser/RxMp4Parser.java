package hu.agocs.rxmp4parser;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.mp4parser.Container;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.builder.DefaultMp4Builder;
import org.mp4parser.muxer.container.mp4.MovieCreator;
import org.mp4parser.muxer.tracks.AppendTrack;
import org.mp4parser.muxer.tracks.ClippedTrack;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import hu.agocs.rxmp4parser.filters.AudioTrackFilter;
import hu.agocs.rxmp4parser.filters.CustomTrackFilter;
import hu.agocs.rxmp4parser.filters.VideoTrackFilter;
import hu.agocs.rxmp4parser.operators.AppendTracks;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.FuncN;

public class RxMp4Parser {

    private static final String TAG = "RxMp4Parser";

    public static Observable<Movie> from(@NonNull final String inputPath) {
        return Observable.create(new Observable.OnSubscribe<Movie>() {
            @Override
            public void call(Subscriber<? super Movie> subscriber) {
                if (new File(inputPath).exists()) {
                    try {
                        subscriber.onNext(MovieCreator.build(inputPath));
                    } catch (IOException e) {
                        Log.e(TAG, e.getMessage());
                        subscriber.onError(e);
                    }
                } else {
                    subscriber.onError(new FileNotFoundException(inputPath));
                }
                subscriber.onCompleted();
            }
        });
    }

    public static Observable<Movie> from(@NonNull final File inputFile) {
        return from(inputFile.getAbsolutePath());
    }

    public static Observable<AppendTrack> appendTracks(@NonNull List<? extends Track> tracks) {
        return Observable.just(tracks)
                .lift(new AppendTracks());
    }

    public static Observable<Movie> mux(@NonNull final Iterable<? extends Track> tracks) {
        return Observable.defer(new Func0<Observable<Movie>>() {
            @Override
            public Observable<Movie> call() {
                return Observable.just(Utils.mux(tracks));
            }
        });
    }

    @Nullable
    public static Observable<Track> extractTrackWithHandler(@NonNull final Movie movie, @NonNull final String handler) {
        return Observable.from(movie.getTracks())
                .filter(new CustomTrackFilter(handler))
                .firstOrDefault(null);
    }

    @Nullable
    public static Observable<Track> extractAudioTrack(@NonNull final Movie movie) {
        return Observable.from(movie.getTracks())
                .filter(new AudioTrackFilter())
                .firstOrDefault(null);
    }

    @Nullable
    public static Observable<Track> extractVideoTrack(final Movie movie) {
        return Observable.from(movie.getTracks())
                .filter(new VideoTrackFilter())
                .firstOrDefault(null);
    }

    @SafeVarargs
    public static Observable<Movie> concatenate(@NonNull Observable<Movie>... input) {
        return Observable.from(input)
                .toList()
                .flatMap(new Func1<List<Observable<Movie>>, Observable<Movie>>() {
                    @Override
                    public Observable<Movie> call(List<Observable<Movie>> observables) {
                        return concatenate(observables);
                    }
                });
    }

    public static Observable<Movie> concatenate(@NonNull Iterable<? extends Observable<Movie>> input) {
        return Observable.zip(input, new FuncN<Iterable<Movie>>() {
            @Override
            public Iterable<Movie> call(Object... args) {
                List<Movie> movies = new ArrayList<>();
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

    @SafeVarargs
    public static Observable<File> concatenateInto(@NonNull final File outputFile, @NonNull Observable<Movie>... input) {
        return concatenate(input).flatMap(new Func1<Movie, Observable<File>>() {
            @Override
            public Observable<File> call(Movie movie) {
                return output(movie, outputFile);
            }
        });
    }

    public static Observable<File> concatenateInto(@NonNull Iterable<? extends Observable<Movie>> input, @NonNull final File outputFile) {
        return concatenate(input).flatMap(new Func1<Movie, Observable<File>>() {
            @Override
            public Observable<File> call(Movie movie) {
                return output(movie, outputFile);
            }
        });
    }

    public static Observable<Movie> crop(@NonNull final Movie movie, final double fromTime, final double toTime) {
        return Observable.defer(new Func0<Observable<Movie>>() {
            @Override
            public Observable<Movie> call() {
                if (toTime < fromTime) {
                    return Observable.error(new RuntimeException("The ending time is earlier than the start time."));
                }

                List<Track> tracks = movie.getTracks();
                movie.setTracks(new LinkedList<Track>());
                // remove all tracks we will create new tracks from the old

                double startTime = fromTime;
                double endTime = toTime;

                boolean timeCorrected = false;

                // Here we try to find a track that has sync samples. Since we can only start decoding
                // at such a sample we SHOULD make sure that the start of the new fragment is exactly
                // such a frame
                for (Track track : tracks) {
                    if (track.getSyncSamples() != null && track.getSyncSamples().length > 0) {
                        if (timeCorrected) {
                            // This exception here could be a false positive in case we have multiple tracks
                            // with sync samples at exactly the same positions. E.g. a single movie containing
                            // multiple qualities of the same video (Microsoft Smooth Streaming file)

                            return Observable.error(new RuntimeException("The startTime has already been corrected by another track with SyncSample. Not Supported."));
                        }
                        startTime = Utils.correctTimeToSyncSample(track, startTime, false);
                        endTime = Utils.correctTimeToSyncSample(track, endTime, true);
                        timeCorrected = true;
                    }
                }
                try {
                    for (Track track : tracks) {
                        long currentSample = 0;
                        double currentTime = 0;
                        double lastTime = -1;
                        long startSample = -1;
                        long endSample = -1;

                        for (int i = 0; i < track.getSampleDurations().length; i++) {
                            long delta = track.getSampleDurations()[i];

                            if (currentTime > lastTime && currentTime <= startTime) {
                                // current sample is still before the new starttime
                                startSample = currentSample;
                            }
                            if (currentTime > lastTime && currentTime <= endTime) {
                                // current sample is after the new start time and still before the new endtime
                                endSample = currentSample;
                            }
                            lastTime = currentTime;
                            currentTime += (double) delta / (double) track.getTrackMetaData().getTimescale();
                            currentSample++;
                        }

                        movie.addTrack(new AppendTrack(new ClippedTrack(track, startSample, endSample)));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return Observable.error(e);
                }
                return Observable.just(movie);
            }
        });
    }

    public static Observable<Movie> crop(@NonNull final String filePath, final double fromTime, final double toTime) {
        return Observable.defer(new Func0<Observable<Movie>>() {
            @Override
            public Observable<Movie> call() {
                Movie movie;
                try {
                    movie = MovieCreator.build(filePath);
                } catch (IOException e) {
                    e.printStackTrace();
                    return Observable.error(e);
                }
                return crop(movie, fromTime, toTime);
            }
        });
    }

    public static Observable<Movie> crop(@NonNull final File inputFile, final double fromTime, final double toTime) {
        return crop(inputFile.getAbsolutePath(), fromTime, toTime);
    }

    public static Observable<File> output(final Movie movie, final File outputFile) {
        return Observable.defer(new Func0<Observable<File>>() {
            @Override
            public Observable<File> call() {
                try {
                    Container output = new DefaultMp4Builder().build(movie);
                    FileOutputStream fos = new FileOutputStream(outputFile);
                    FileChannel fc = fos.getChannel();
                    fc.position(0);
                    output.writeContainer(fc);
                    fc.close();
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    return Observable.error(e);
                }
                return Observable.just(outputFile);
            }
        });
    }


}
