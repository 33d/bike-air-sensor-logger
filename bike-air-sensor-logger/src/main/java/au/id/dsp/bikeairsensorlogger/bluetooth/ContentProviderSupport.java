/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Created by damien on 18/10/15.
 */
public class ContentProviderSupport {
    private static final String TAG = "ContentProviderSupport";

    /**
     * Interface to write a stream of data to a pipe.  Use with
     * {@link ContentProvider#openPipeHelper}.
     */
    public interface PipeDataWriter<T> {
        /**
         * Called from a background thread to stream data out to a pipe.
         * Note that the pipe is blocking, so this thread can block on
         * writes for an arbitrary amount of time if the client is slow
         * at reading.
         *
         * @param output The pipe where data should be written.  This will be
         * closed for you upon returning from this function.
         * @param uri The URI whose data is to be written.
         * @param mimeType The desired type of data to be written.
         * @param opts Options supplied by caller.
         * @param args Your own custom arguments.
         */
        public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType,
                                    Bundle opts, T args);
    }
    /**
     * A helper function for implementing {@link #openTypedAssetFile}, for
     * creating a data pipe and background thread allowing you to stream
     * generated data back to the client.  This function returns a new
     * ParcelFileDescriptor that should be returned to the caller (the caller
     * is responsible for closing it).
     *
     * @param uri The URI whose data is to be written.
     * @param mimeType The desired type of data to be written.
     * @param opts Options supplied by caller.
     * @param args Your own custom arguments.
     * @param func Interface implementing the function that will actually
     * stream the data.
     * @return Returns a new ParcelFileDescriptor holding the read side of
     * the pipe.  This should be returned to the caller for reading; the caller
     * is responsible for closing it when done.
     */
    public static <T> ParcelFileDescriptor openPipeHelper(final Uri uri, final String mimeType,
                                                   final Bundle opts, final T args, final PipeDataWriter<T> func)
            throws FileNotFoundException {
        try {
            final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
            AsyncTask<Object, Object, Object> task = new AsyncTask<Object, Object, Object>() {
                @Override
                protected Object doInBackground(Object... params) {
                    func.writeDataToPipe(fds[1], uri, mimeType, opts, args);
                    try {
                        fds[1].close();
                    } catch (IOException e) {
                        Log.w(TAG, "Failure closing pipe", e);
                    }
                    return null;
                }
            };
            task.execute();
            return fds[0];
        } catch (IOException e) {
            throw new FileNotFoundException("failure making pipe");
        }
    }

}
