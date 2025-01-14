package com.atre.movies.api;

import com.atre.movies.model.Movie;
import com.google.gson.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@DependsOn("omdbClientFactory")
public class Movies {

    private static final Gson gson;

    @Value("${app.search.maxresultscap}")
    private int MAX_RESULTS_CAP = 100;

    private static final Logger logger = LoggerFactory.getLogger(Movies.class);

    static {
        GsonBuilder gsonBuilder = new GsonBuilder();

        JsonDeserializer<Movie> deserializer = new JsonDeserializer<Movie>() {
            @Override
            public Movie deserialize(JsonElement jsonElement, Type type,
                                     JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
                JsonObject json = jsonElement.getAsJsonObject();
                String title = json.get("Title").getAsString();
                String year = json.get("Year").getAsString();
                String releaseDate = json.get("Released").getAsString();
                String genre = json.get("Genre").getAsString();
                String actors = json.get("Actors").getAsString();
                String plot = json.get("Plot").getAsString();
                String posterUrl = json.get("Poster").getAsString();
                String imdbId = json.get("imdbID").getAsString();
                String rtRating = "N/A";

                JsonArray ratings = json.get("Ratings").getAsJsonArray();
                for (int i = 0; i < ratings.size(); i++) {
                    JsonObject rating = ratings.get(i).getAsJsonObject();
                    if (rating.get("Source").getAsString().equals("Rotten Tomatoes")) {
                        rtRating = rating.get("Value").getAsString();
                    }
                }

                return new Movie(title, year, releaseDate, genre, actors, plot, posterUrl, imdbId, rtRating);
            }
        };
        gsonBuilder.registerTypeAdapter(Movie.class, deserializer);

        gson = gsonBuilder.create();
    }

    private final OmdbClientFactory omdbClientFactory;

    @Autowired
    public Movies(OmdbClientFactory omdbClientFactory) {
        this.omdbClientFactory = omdbClientFactory;
    }

    @PostConstruct
    public void init() {
        logger.info("MAX_RESULTS_CAP = {}", MAX_RESULTS_CAP);
    }

    /**
     *
     * @param movieTitle
     * @param maxResults
     * @return
     */
    public List<Movie> search(String movieTitle, int maxResults) {
        if (movieTitle == null || movieTitle.isBlank()) {
            throw new IllegalArgumentException("movieTitle must not be null or empty\n");
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS_CAP) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + MAX_RESULTS_CAP + "\n");
        }
        OmdbClient omdbClient = omdbClientFactory.getOmdbClient();
        List<String> movieIds = omdbClient.search(movieTitle, maxResults);
        if (movieIds.isEmpty()) {
            return Collections.emptyList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(movieIds.size());
        List<CompletableFuture<Movie>> futures = new ArrayList<>();

        for (String movieId : movieIds) {
            // Submit an asynchronous task to call the API
            CompletableFuture<Movie> future = CompletableFuture.supplyAsync(() -> {
                String threadName = Thread.currentThread().getName();
                logger.info("Thread: {}, movieId: {}", threadName, movieId);
                String json = omdbClient.getByImdbId(movieId);
                return gson.fromJson(json, Movie.class);
            }, executor);

            futures.add(future);
        }

        // Wait for all futures to complete and collect the results
        List<Movie> movies = new ArrayList<>();
        for (CompletableFuture<Movie> future : futures) {
            try {
                movies.add(future.get());
            } catch (ExecutionException | InterruptedException e) {
                logger.error("future.get() failed", e);
            }
        }
        executor.shutdown();

        return movies;
    }

//    public static List<Movie> search(String movieTitle) {
//        List<String> movieIds = getMovieIds(movieTitle);
//
//        List<Movie> movies = new ArrayList<>();
//
//        for (String movieId : movieIds) {
//            String json = OmdbClient.getByImdbId(movieId);
//            Movie movie = gson.fromJson(json, Movie.class);
//            movies.add(movie);
//        }
//        return movies;
//    }
//
//    private static List<String> getMovieIds(String movieTitle) {
//        List<String> tmdbIds = TmdbClient.search(movieTitle);
//
//        return tmdbIds.stream().map(TmdbClient::getImdbId)
//                .filter(Optional::isPresent)
//                .map(Optional::get)
//                .collect(Collectors.toList());
//
////        List<String> imdbIds = new ArrayList<>();
////        for (int i = 0; i < tmdbIds.size(); i++) {
////            String imdbId = TmdbClient.getImdbId(tmdbIds.get(i));
////            if (!(imdbId.equals("null") || imdbId.equals(""))) {
////                imdbIds.add(imdbId);
////            }
////        }
////        return imdbIds;
//    }
}