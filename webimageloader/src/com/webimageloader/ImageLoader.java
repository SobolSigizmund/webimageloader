package com.webimageloader;

import java.io.File;
import java.io.IOException;
import java.net.URLStreamHandler;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import com.webimageloader.content.ContentURLStreamHandler;
import com.webimageloader.ext.ImageHelper;
import com.webimageloader.loader.DiskLoader;
import com.webimageloader.loader.LoaderManager;
import com.webimageloader.loader.MemoryCache;
import com.webimageloader.loader.NetworkLoader;
import com.webimageloader.transformation.Transformation;
import com.webimageloader.util.WaitFuture;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * This is the main class of WebImageLoader which can be constructed using a
 * {@link Builder}. It's often more convenient to use the provided
 * {@link ImageHelper} to load images.
 *
 * @author Alexander Blom <alexanderblom.se>
 */
public class ImageLoader {
    private static final String TAG = "ImageLoader";

    private static final LoaderManager.Listener EMPTY_LISTENER = new LoaderManager.Listener() {
        @Override
        public void onLoaded(Bitmap b) {}

        @Override
        public void onError(Throwable t) {}
    };

    /**
     * Listener for a request which will always be called on the main thread of
     * the application
     *
     * @author Alexander Blom <alexanderblom.se>
     *
     * @param <T> the tag class
     */
    public interface Listener<T> {
        /**
         * Called if the request succeeded
         *
         * @param tag the tag which was passed in
         * @param b the resulting bitmap
         */
        void onSuccess(T tag, Bitmap b);

        /**
         * Called if the request failed
         *
         * @param tag the tag which was passed in
         * @param t the reason the request failed
         */
        void onError(T tag, Throwable t);
    }

    private LoaderManager loaderManager;
    private HandlerManager handlerManager;

    private ImageLoader(LoaderManager loaderManager) {
        this.loaderManager = loaderManager;

        handlerManager = new HandlerManager();
    }

    /**
     * Get memory cache debug info
     *
     * @return debug info or null if not available
     */
    public MemoryCache.DebugInfo getMemoryCacheInfo() {
        MemoryCache memoryCache = loaderManager.getMemoryCache();

        if (memoryCache != null) {
            return memoryCache.getDebugInfo();
        } else {
            return null;
        }
    }

    /**
     * Get the memory cache
     *
     * @return memory cache or null if not available
     */
    public MemoryCache getMemoryCache() {
        return loaderManager.getMemoryCache();
    }

    /**
     * Load the specified request blocking the calling thread.
     *
     * @param url the url to load
     * @return the bitmap
     * @throws IOException if the load failed
     *
     * @see #loadBlocking(Request)
     */
    public Bitmap loadBlocking(String url) throws IOException {
        return loadBlocking(new Request(url));
    }

    /**
     * Load the specified request blocking the calling thread.
     *
     * @param url the url to load
     * @param transformation can be null
     * @return the bitmap
     * @throws IOException if the load failed
     *
     * @see #loadBlocking(Request)
     */
    public Bitmap loadBlocking(String url, Transformation transformation) throws IOException {
        return loadBlocking(new Request(url).withTransformation(transformation));
    }

    /**
     * Load the specified request blocking the calling thread.
     *
     * @param request the request to load
     * @return the bitmap
     * @throws IOException if the load failed
     */
    public Bitmap loadBlocking(Request request) throws IOException {
        final WaitFuture future = new WaitFuture();

        Bitmap b = load(new Object(), request, new LoaderManager.Listener() {
            @Override
            public void onLoaded(Bitmap b) {
                future.set(b);
            }

            @Override
            public void onError(Throwable t) {
                future.setException(t);
            }
        });

        if (b != null) {
            return b;
        }

        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

            // Rethrow as original exception if possible
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException("Failed to fetch image", e.getCause());
            }
        } catch (InterruptedException e) {
            throw new IOException("Interruped while fetching image", e);
        }
    }

    /**
     * Used to prime the file and memory cache. It's safe to later call load
     * with the same request, it will automatically be reused.
     *
     * @param url which resource to get
     *
     * @see #preload(Request)
     */
    public void preload(String url) {
        preload(new Request(url));
    }

    /**
     * Used to prime the file and memory cache. It's safe to later call load
     * with the same request, it will automatically be reused.
     *
     * @param url which resource to get
     * @param transformation can be null
     *
     * @see #preload(Request)
     */
    public void preload(String url, Transformation transformation) {
        preload(new Request(url).withTransformation(transformation));
    }

    /**
     * Used to prime the file and memory cache. It's safe to later call load
     * with the same request, it will automatically be reused.
     *
     * @param request the request to preload
     */
    public void preload(Request request) {
        load(new Object(), request, EMPTY_LISTENER);
    }

    /**
     * Load an image from an url with the given listener. Previously pending
     * request for this tag will be automatically cancelled.
     *
     * @param tag used to determine when we this request should be cancelled
     * @param url which resource to get
     * @param listener called when the request has finished or failed
     * @return the bitmap if it was already loaded
     *
     * @see #load(Object, Request, Listener)
     */
    public <T> Bitmap load(T tag, String url, Listener<T> listener) {
        return load(tag, new Request(url), listener);
    }


    /**
     * Load an image from an url with the given listener. Previously pending
     * request for this tag will be automatically cancelled.
     *
     * @param tag used to determine when we this request should be cancelled
     * @param url which resource to get
     * @param transformation can be null
     * @param listener called when the request has finished or failed
     * @return the bitmap if it was already loaded
     *
     * @see #load(Object, Request, Listener)
     */
    public <T> Bitmap load(T tag, String url, Transformation transformation, Listener<T> listener) {
        return load(tag, new Request(url).withTransformation(transformation), listener);
    }

    /**
     * Load an image from an url with the given listener. Previously pending
     * request for this tag will be automatically cancelled.
     *
     * @param tag used to determine when we this request should be cancelled
     * @param request what to to fetch
     * @param listener called when the request has finished or failed
     * @return the bitmap if it was already loaded
     */
    public <T> Bitmap load(T tag, Request request,  Listener<T> listener) {
        return load(tag, request, handlerManager.getListener(tag, listener));
    }

    /**
     * Cancel any pending requests for this tag.
     *
     * @param tag the tag
     */
    public <T> void cancel(T tag) {
        handlerManager.cancel(tag);
        loaderManager.cancel(tag);
    }

    private Bitmap load(Object tag, Request request, LoaderManager.Listener listener) {
        return loaderManager.load(tag, request.toLoaderRequest(), listener);
    }

    public void destroy() {
        loaderManager.close();
    }

    private static class HandlerManager {
        private Handler handler;

        public HandlerManager() {
            handler = new Handler(Looper.getMainLooper());
        }

        public <T> LoaderManager.Listener getListener(T tag, Listener<T> listener) {
            // It's possible there is already a callback in progress for this tag
            // so we'll remove it
            handler.removeCallbacksAndMessages(tag);

            return new TagListener<T>(tag, listener);
        }

        public void cancel(Object tag) {
            handler.removeCallbacksAndMessages(tag);
        }

        private class TagListener<T> implements LoaderManager.Listener {
            private T tag;
            private Listener<T> listener;

            public TagListener(T tag, Listener<T> listener) {
                this.tag = tag;
                this.listener = listener;
            }

            @Override
            public void onLoaded(final Bitmap b) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onSuccess(tag, b);
                    }
                });
            }

            @Override
            public void onError(final Throwable t) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onError(tag, t);
                    }
                });
            }

            private void post(Runnable r) {
                Message m = Message.obtain(handler, r);
                m.obj = tag;
                handler.sendMessage(m);
            }
        }
    }

    /**
     * Builder class used to construct a {@link ImageLoader}.
     *
     * @author Alexander Blom <alexanderblom.se>
     */
    public static class Builder {
        private HashMap<String, URLStreamHandler> streamHandlers;

        private DiskLoader diskLoader;
        private MemoryCache memoryCache;

        private int connectionTimeout;
        private int readTimeout;
        private long maxAge;

        public Builder() {
            streamHandlers = new HashMap<String, URLStreamHandler>();
        }

        public Builder enableDiskCache(File cacheDir, int maxSize) {
            try {
                diskLoader = DiskLoader.open(cacheDir, maxSize);
            } catch (IOException e) {
                Log.e(TAG, "Disk cache not available", e);
            }

            return this;
        }

        public Builder enableMemoryCache(int maxSize) {
            memoryCache = new MemoryCache(maxSize);

            return this;
        }

        public Builder supportResources(ContentResolver resolver) {
            URLStreamHandler handler = new ContentURLStreamHandler(resolver);
            streamHandlers.put(ContentResolver.SCHEME_CONTENT, handler);
            streamHandlers.put(ContentResolver.SCHEME_FILE, handler);
            streamHandlers.put(ContentResolver.SCHEME_ANDROID_RESOURCE, handler);

            return this;
        }

        public Builder addURLSchemeHandler(String scheme, URLStreamHandler handler) {
            streamHandlers.put(scheme, handler);

            return this;
        }

        public Builder setConnectionTimeout(int connectionTimeout) {
            this.connectionTimeout = connectionTimeout;

            return this;
        }

        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;

            return this;
        }

        public Builder setCacheMaxAge(long maxAge) {
            this.maxAge = maxAge;

            return this;
        }

        public ImageLoader build() {
            NetworkLoader networkLoader = new NetworkLoader(streamHandlers, connectionTimeout, readTimeout, maxAge);
            LoaderManager loaderManager = new LoaderManager(memoryCache, diskLoader, networkLoader);

            return new ImageLoader(loaderManager);
        }
    }

    public static class Logger {
        public static boolean DEBUG = false;
        public static boolean VERBOSE = false;

        /**
         * Log both debug and verbose messages
         */
        public static void logAll() {
            DEBUG = true;
            VERBOSE = true;
        }

        private Logger() {}
    }
}
