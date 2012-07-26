package com.webimageloader.loader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.webimageloader.concurrent.ListenerFuture;
import com.webimageloader.util.DiskLruCache;
import com.webimageloader.util.Hasher;
import com.webimageloader.util.IOUtil;
import com.webimageloader.util.PriorityThreadFactory;
import com.webimageloader.util.DiskLruCache.Editor;
import com.webimageloader.util.DiskLruCache.Snapshot;

import android.graphics.Bitmap;
import android.os.Process;
import android.util.Log;

public class DiskLoader extends BackgroundLoader implements Closeable {
    private static final String TAG = "DiskLoader";

    private static final int APP_VERSION = 1;

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private static final Bitmap.CompressFormat COMPRESS_FORMAT = Bitmap.CompressFormat.JPEG;
    private static final int COMPRESS_QUALITY = 75;

    private static final int INPUT_IMAGE = 0;
    private static final int INPUT_METADATA = 1;
    private static final int VALUE_COUNT = 1;

    private DiskLruCache cache;
    private Hasher hasher;

    public static DiskLoader open(File directory, long maxSize) throws IOException {
        return new DiskLoader(DiskLruCache.open(directory, APP_VERSION, VALUE_COUNT, maxSize));
    }

    @Override
    protected ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(new PriorityThreadFactory("Disk", Process.THREAD_PRIORITY_BACKGROUND));
    }

    private DiskLoader(DiskLruCache cache) {
        this.cache = cache;

        hasher = new Hasher();
    }

    @Override
    public void close() {
        super.close();

        IOUtil.closeQuietly(cache);
    }

    @Override
    protected void loadInBackground(LoaderRequest request, Iterator<Loader> chain, Listener listener) throws IOException {
        String key = hashKeyForDisk(request);
        Snapshot snapshot = cache.get(key);
        if (snapshot != null) {
            try {
                Log.v(TAG, "Loaded " + request + " from disk");

                InputStream is = snapshot.getInputStream(INPUT_IMAGE);
                listener.onStreamLoaded(is);
                is.close();

            } finally {
                snapshot.close();
            }
        } else {
            // We need to get the next loader
            Loader next = chain.next();
            next.load(request, chain, new NextListener(request, listener));
        }
    }

    private class NextListener implements Listener {
        private LoaderRequest request;
        private Listener listener;

        public NextListener(LoaderRequest request, Listener listener) {
            this.request = request;
            this.listener = listener;
        }

        @Override
        public void onStreamLoaded(InputStream is) {
            try {
                String key = hashKeyForDisk(request);
                Editor editor = cache.edit(key);
                if (editor == null) {
                    throw new IOException("File is already being edited");
                }

                OutputStream os = new BufferedOutputStream(editor.newOutputStream(INPUT_IMAGE));
                try {
                    copy(new BufferedInputStream(is), os);
                    editor.commit();

                    // Read back the file we just saved
                    run(request, listener, new ReadTask(request));
                } catch (IOException e) {
                    // We failed writing to the cache, we can't really do
                    // anything to clean this up
                    editor.abort();
                    listener.onError(e);
                } finally {
                    IOUtil.closeQuietly(os);
                }
            } catch (IOException e) {
                // We failed opening the cache, this
                // means that the InputStream is still untouched.
                // Pass it trough to the listener without caching.
                Log.e(TAG, "Failed opening cache", e);
                listener.onStreamLoaded(is);
            }
        }

        @Override
        public void onBitmapLoaded(Bitmap b) {
            try {
                String key = hashKeyForDisk(request);
                Editor editor = cache.edit(key);
                if (editor == null) {
                    throw new IOException("File is already being edited");
                }

                OutputStream os = new BufferedOutputStream(editor.newOutputStream(INPUT_IMAGE));
                try {
                    // TODO: Maybe guess format from url
                    b.compress(COMPRESS_FORMAT, COMPRESS_QUALITY, os);
                    editor.commit();
                } catch (IOException e) {
                    // We failed writing to the cache
                    editor.abort();
                    throw e;
                } finally {
                    IOUtil.closeQuietly(os);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed saving bitmap to cache", e);
            }

            // We can always pass on the bitmap we got, even if
            // we didn't manage to write it to cache
            listener.onBitmapLoaded(b);
        }

        @Override
        public void onError(Throwable t) {
            listener.onError(t);
        }
    }

    private class ReadTask implements ListenerFuture.Task {
        private LoaderRequest request;

        public ReadTask(LoaderRequest request) {
            this.request = request;
        }

        @Override
        public void run(Listener listener) throws Exception {
            String key = hashKeyForDisk(request);

            Snapshot snapshot = cache.get(key);
            if (snapshot == null) {
                throw new IllegalStateException("File not available");
            }

            try {
                InputStream is = snapshot.getInputStream(INPUT_IMAGE);
                listener.onStreamLoaded(is);
                is.close();
            } finally {
                snapshot.close();
            }
        }
    }

    private static int copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int count = 0;
        int n = 0;
        while (-1 != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    /**
     * A hashing method that changes a string (like a URL) into a hash suitable
     * for using as a disk filename.
     */
    private String hashKeyForDisk(LoaderRequest request) {
        String key = request.getCacheKey();

        // We don't except to have a lot of threads
        // so it's okay to synchronize access

        synchronized (hasher) {
            return hasher.hash(key);
        }
    }
}
