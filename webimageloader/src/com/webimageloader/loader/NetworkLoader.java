package com.webimageloader.loader;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.webimageloader.Constants;
import com.webimageloader.ImageLoader.Logger;
import com.webimageloader.util.Android;
import com.webimageloader.util.HeaderParser;
import com.webimageloader.util.InputSupplier;
import com.webimageloader.util.PriorityThreadFactory;

public class NetworkLoader extends BackgroundLoader {
    private static final String TAG = "NetworkLoader";

    private static final int TAG_REGULAR = 0x7eb00000;
    private static final int TAG_CONDITIONAL = 0x7eb0000c;

    private Map<String, URLStreamHandler> streamHandlers;
    private int connectTimeout;
    private int readTimeout;
    private long defaultMaxAge;
    private long forcedMaxAge;

    public NetworkLoader(Map<String, URLStreamHandler> streamHandlers, int connectionTimeout, int readTimeout, long defaultMaxAge, long forcedMaxAge) {
        this.streamHandlers = Collections.unmodifiableMap(streamHandlers);
        this.connectTimeout = connectionTimeout;
        this.readTimeout = readTimeout;
        this.defaultMaxAge = defaultMaxAge;
        this.forcedMaxAge = forcedMaxAge;
    }

    @Override
    protected ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(2, new PriorityThreadFactory("Network", Process.THREAD_PRIORITY_BACKGROUND));
    }

    @Override
    protected void loadInBackground(LoaderRequest request, Iterator<Loader> chain, Listener listener) throws Exception {
        String url = request.getUrl();

        String protocol = getProtocol(url);
        URLStreamHandler streamHandler = getURLStreamHandler(protocol);

        URLConnection urlConnection = openConnection(new URL(null, url, streamHandler));

        Metadata metadata = request.getMetadata();
        if (metadata != null) {
            tag(TAG_CONDITIONAL);

            // We have some information available
            long modifiedSince = metadata.getLastModified();
            if (modifiedSince != 0) {
                urlConnection.setIfModifiedSince(modifiedSince);
            }

            String etag = metadata.getEtag();
            if (!TextUtils.isEmpty(etag)) {
                urlConnection.addRequestProperty("If-None-Match", etag);
            }
        } else {
            tag(TAG_REGULAR);
        }

        String contentType = urlConnection.getContentType();
        long lastModified = urlConnection.getLastModified();
        long expires = getExpires(urlConnection);
        String etag = urlConnection.getHeaderField("ETag");

        // Update metadata
        metadata = new Metadata(contentType, lastModified, expires, etag);

        if (getResponseCode(urlConnection) == HttpURLConnection.HTTP_NOT_MODIFIED) {
            if (Logger.VERBOSE) Log.v(TAG, request + " was not modified since last fetch");

            listener.onNotModified(metadata);
        } else {
            if (Logger.VERBOSE) Log.v(TAG, "Loaded " + request + " from network");

            listener.onStreamLoaded(new NetworkInputSupplier(urlConnection), metadata);
        }
    }

    private int getResponseCode(URLConnection urlConnection) throws IOException {
        // We can't assume we have a HttpUrlConnection as resources uses a custom subclass
        if (urlConnection instanceof HttpURLConnection) {
            return ((HttpURLConnection) urlConnection).getResponseCode();
        } else {
            return -1;
        }
    }

    private long getExpires(URLConnection urlConnection) {
        if (forcedMaxAge > 0) {
            return System.currentTimeMillis() + forcedMaxAge;
        } else if (forcedMaxAge == Constants.MAX_AGE_INFINITY) {
            return Metadata.NEVER_EXPIRES;
        }

        // Prefer "max-age" before "expires"
        long maxAge = HeaderParser.getMaxAge(urlConnection);
        if (maxAge > 0) {
            return System.currentTimeMillis() + maxAge * 1000;
        }

        long expires = urlConnection.getExpiration();
        if (expires > 0) {
            return expires;
        }

        // Use default
        return System.currentTimeMillis() + defaultMaxAge;
    }
    
    private URLConnection openConnection(URL url) throws IOException {
        disableConnectionReuseIfNecessary();

        URLConnection urlConnection = url.openConnection();
        
        if (connectTimeout > 0) {
            urlConnection.setConnectTimeout(connectTimeout);
        }

        if (readTimeout > 0) {
            urlConnection.setReadTimeout(readTimeout);
        }
        
        return urlConnection;
    }

    @TargetApi(14)
    private void tag(int tag) {
        if (Android.isAPI(14)) {
            TrafficStats.setThreadStatsTag(tag);
        }
    }

    /**
     * Workaround for bug pre-Froyo, see here for more info:
     * http://android-developers.blogspot.com/2011/09/androids-http-clients.html
     */
    private static void disableConnectionReuseIfNecessary() {
        // HTTP connection reuse which was buggy pre-froyo
        if (!Android.isAPI(8)) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    private static String getProtocol(String url) {
        int i = url.indexOf(':');
        return i == -1 ? null : url.substring(0, i);
    }

    private URLStreamHandler getURLStreamHandler(String protocol) {
        return streamHandlers.get(protocol);
    }
    
    private class NetworkInputSupplier implements InputSupplier {
        private URLConnection connection;
        private URL url;
        
        public NetworkInputSupplier(URLConnection connection) {
            this.connection = connection;
            
            url = connection.getURL();
        }
        
        @Override
        public InputStream getInput() throws IOException {
            if (connection != null) {
                InputStream is = connection.getInputStream();
                connection = null;
                
                return is;
            } else {
                return openConnection(url).getInputStream();
            }
        }
        
    }
}
