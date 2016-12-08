package com.hiroshi.cimoc.core;

import com.hiroshi.cimoc.CimocApplication;
import com.hiroshi.cimoc.core.manager.SourceManager;
import com.hiroshi.cimoc.core.parser.Parser;
import com.hiroshi.cimoc.core.parser.SearchIterator;
import com.hiroshi.cimoc.model.Chapter;
import com.hiroshi.cimoc.model.Comic;
import com.hiroshi.cimoc.model.ImageUrl;
import com.hiroshi.cimoc.model.Pair;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;

/**
 * Created by Hiroshi on 2016/8/20.
 */
public class Manga {

    private static OkHttpClient mClient = CimocApplication.getHttpClient();

    public static Observable<Comic> getSearchResult(final int source, final String keyword, final int page) {
        return Observable.create(new Observable.OnSubscribe<Comic>() {
            @Override
            public void call(Subscriber<? super Comic> subscriber) {
                Parser parser = SourceManager.getParser(source);
                Request request = parser.getSearchRequest(keyword, page);
                Random random = new Random();
                try {
                    String html = getResponseBody(mClient, request);
                    SearchIterator iterator = parser.getSearchIterator(html, page);
                    if (iterator == null || iterator.empty()) {
                        throw new EmptyResultException();
                    }
                    while (iterator.hasNext()) {
                        subscriber.onNext(iterator.next());
                        Thread.sleep(random.nextInt(200));
                    }
                    subscriber.onCompleted();
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    public static Observable<List<Chapter>> getComicInfo(final Comic comic) {
        return Observable.create(new Observable.OnSubscribe<List<Chapter>>() {
            @Override
            public void call(Subscriber<? super List<Chapter>> subscriber) {
                Parser parser = SourceManager.getParser(comic.getSource());
                Request request = parser.getInfoRequest(comic.getCid());
                try {
                    String html = getResponseBody(mClient, request);
                    parser.parseInfo(html, comic);
                    request = parser.getChapterRequest(html, comic.getCid());
                    if (request != null) {
                        html = getResponseBody(mClient, request);
                    }
                    List<Chapter> list = parser.parseChapter(html);
                    if (!list.isEmpty()) {
                        subscriber.onNext(list);
                        subscriber.onCompleted();
                    } else {
                        throw new ParseErrorException();
                    }
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    public static Observable<List<Comic>> getRecentComic(final int source, final int page) {
        return Observable.create(new Observable.OnSubscribe<List<Comic>>() {
            @Override
            public void call(Subscriber<? super List<Comic>> subscriber) {
                Parser parser = SourceManager.getParser(source);
                Request request = parser.getRecentRequest(page);
                try {
                    String html = getResponseBody(mClient, request);
                    List<Comic> list = parser.parseRecent(html, page);
                    if (list.isEmpty()) {
                        throw new EmptyResultException();
                    } else {
                        subscriber.onNext(list);
                        subscriber.onCompleted();
                    }
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    public static Observable<List<ImageUrl>> getChapterImage(final int source, final String cid, final String path) {
        return Observable.create(new Observable.OnSubscribe<List<ImageUrl>>() {
            @Override
            public void call(Subscriber<? super List<ImageUrl>> subscriber) {
                Parser parser = SourceManager.getParser(source);
                Request request = parser.getImageServerRequest();
                String html;
                try {
                    if (request != null) {
                        html = getResponseBody(mClient, request);
                        parser.parseImageServer(html);
                    }
                    request = parser.getImagesRequest(cid, path);
                    html = getResponseBody(mClient, request);
                    List<ImageUrl> list = parser.parseImages(html);
                    if (list.isEmpty()) {
                        throw new ParseErrorException();
                    } else {
                        subscriber.onNext(list);
                        subscriber.onCompleted();
                    }
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    public static List<ImageUrl> getImageUrls(OkHttpClient client, int source, String cid, String path) throws InterruptedIOException {
        List<ImageUrl> list = new ArrayList<>();
        Parser parser = SourceManager.getParser(source);
        Request request = parser.getImageServerRequest();
        Response response = null;
        try {
            if (request != null) {
                response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    parser.parseImageServer(response.body().string());
                } else {
                    throw new NetworkErrorException();
                }
                response.close();
            }
            request = parser.getImagesRequest(cid, path);
            response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                list.addAll(parser.parseImages(response.body().string()));
            } else {
                throw new NetworkErrorException();
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return list;
    }

    public static String getLazyUrl(OkHttpClient client, int source, String url) throws InterruptedIOException {
        Parser parser = SourceManager.getParser(source);
        Request request = parser.getLazyRequest(url);
        Response response = null;
        try {
            response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                return parser.parseLazy(response.body().string(), url);
            } else {
                throw new NetworkErrorException();
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return null;
    }

    public static Observable<String> loadLazyUrl(final int source, final String url) {
        return Observable.create(new Observable.OnSubscribe<String>() {
            @Override
            public void call(Subscriber<? super String> subscriber) {
                Parser parser = SourceManager.getParser(source);
                Request request = parser.getLazyRequest(url);
                String newUrl = null;
                try {
                    newUrl = parser.parseLazy(getResponseBody(mClient, request), url);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                subscriber.onNext(newUrl);
                subscriber.onCompleted();
            }
        }).subscribeOn(Schedulers.io());
    }

    public static Observable<Pair<Comic, Pair<Integer, Integer>>> checkUpdate(final List<Comic> list) {
        return Observable.create(new Observable.OnSubscribe<Pair<Comic, Pair<Integer, Integer>>>() {
            @Override
            public void call(Subscriber<? super Pair<Comic, Pair<Integer, Integer>>> subscriber) {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(1500, TimeUnit.MILLISECONDS)
                        .readTimeout(1500, TimeUnit.MILLISECONDS)
                        .build();
                int size = list.size();
                int count = 0;
                for (Comic comic : list) {
                    Pair<Comic, Pair<Integer, Integer>> pair = Pair.create(comic, Pair.create(++count, size));
                    if (comic.getSource() < 100) {
                        Parser parser = SourceManager.getParser(comic.getSource());
                        Request request = parser.getCheckRequest(comic.getCid());
                        try {
                            String update = parser.parseCheck(getResponseBody(client, request));
                            if (comic.getUpdate() != null && !comic.getUpdate().equals(update)) {
                                comic.setUpdate(update);
                                comic.setHighlight(true);
                                subscriber.onNext(pair);
                                continue;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    pair.first = null;
                    subscriber.onNext(pair);
                }
                subscriber.onCompleted();
            }
        }).subscribeOn(Schedulers.io());
    }

    private static String getResponseBody(OkHttpClient client, Request request) throws NetworkErrorException {
        Response response = null;
        try {
            response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                return response.body().string();
            }
        } catch (Exception e) {
            throw new NetworkErrorException();
        } finally {
            if (response != null) {
                response.close();
            }
        }
        throw new NetworkErrorException();
    }

    public static class ParseErrorException extends Exception {}

    public static class NetworkErrorException extends Exception {}

    public static class EmptyResultException extends Exception {}

}
