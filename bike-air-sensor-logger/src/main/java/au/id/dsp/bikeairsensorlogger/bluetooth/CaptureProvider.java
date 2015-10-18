package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.OpenableColumns;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * Created by damien on 11/10/15.
 */
public class CaptureProvider extends ContentProvider {

    public static final String AUTHORITY = "au.id.dsp.bikeairsensorlogger.bluetooth.CaptureProvider";
    public static final int CAPTURES = 100;
    public static final int CAPTURE = 110;
    public static final int CAPTURES_WITH_COUNTS = 120;
    public static final int CAPTURE_CSV = 130;
    public static final Uri CAPTURES_URI = Uri.parse("content://" + AUTHORITY + "/captures");
    public static final Uri CAPTURES_WITH_COUNTS_URI = Uri.parse("content://" + AUTHORITY + "/captureswithcounts");
    public static final Uri CAPTURE_CSV_URI = Uri.parse("content://" + AUTHORITY + "/captures/csv");

    public static final String CONTENT_ITEM_TYPE = ContentResolver.CURSOR_ITEM_BASE_TYPE + "/captures";
    public static final String CONTENT_TYPE = ContentResolver.CURSOR_DIR_BASE_TYPE + "/captures";

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        uriMatcher.addURI(AUTHORITY, "captures", CAPTURES);
        uriMatcher.addURI(AUTHORITY, "captures/#", CAPTURE);
        uriMatcher.addURI(AUTHORITY, "captureswithcounts", CAPTURES_WITH_COUNTS);
        uriMatcher.addURI(AUTHORITY, "captures/csv/#", CAPTURE_CSV);
    }

    private static final String[] exportColumns
            = new String[] { "LATITUDE", "LONGITUDE", "ACCURACY", "TIME", "TEXT" };

    private LogDatabase db;

    @Override
    public boolean onCreate() {
        db = new LogDatabase(getContext());
        return true;
    }

    @Nullable
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectArgs, String sortOrder) {
        SQLiteQueryBuilder builder = new SQLiteQueryBuilder();

        switch (uriMatcher.match(uri)) {
            case CAPTURE:
                builder.setTables("CAPTURE");
                builder.appendWhere(BaseColumns._ID + "=" + uri.getLastPathSegment());
                break;
            case CAPTURES:
                builder.setTables("CAPTURE");
                break;
            case CAPTURES_WITH_COUNTS:
                builder.setTables("CAPTURECOUNTS");
                break;
            case CAPTURE_CSV: {
                // Supply filenames for these
                String id = uri.getLastPathSegment();
                MatrixCursor cursor = new MatrixCursor(projection, 1);
                Object[] values = new Object[projection.length];
                for (int i = 0; i < projection.length; i++)
                    if (OpenableColumns.DISPLAY_NAME.equals(projection[i]))
                        values[i] = id + ".csv";
                    else if (OpenableColumns.SIZE.equals(projection[i]))
                        values[i] = 1; // Lie to Gmail
                cursor.addRow(values);
                return cursor;
            }
            default:
                return null;
        }

        Cursor cursor = builder.query(db.db, projection, selection, selectArgs, null, null, sortOrder);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Override
    public int delete(Uri uri, String s, String[] strings) {
        switch (uriMatcher.match(uri)) {
            case CAPTURE:
                int r = db.db.delete("CAPTURE",
                        BaseColumns._ID + "=?",
                        new String[] { uri.getLastPathSegment() });
                getContext().getContentResolver().notifyChange(uri, null);
        }
        return 0;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case CAPTURE_CSV:
                return "*/*";
            default:
                return null;
        }
    }

    private final ContentProviderSupport.PipeDataWriter<String> csvWriter = new ContentProviderSupport.PipeDataWriter<String>() {
        @Override
        public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType, Bundle opts, String id) {
            try {
                Writer out = new OutputStreamWriter(new ParcelFileDescriptor.AutoCloseOutputStream(output), "US-ASCII");

                Cursor cursor = db.db.query("LOG",
                        exportColumns,
                        "CAPTURE=?", new String[]{id},
                        null, null, "TIME", null);
                final int[] colIDs = new int[exportColumns.length];
                for (int i = 0; i < exportColumns.length; i++)
                    colIDs[i] = cursor.getColumnIndexOrThrow(exportColumns[i]);

                // Write the heading row
                out.write(exportColumns[0]);
                for (int i = 1; i < exportColumns.length; i++) {
                    out.write(',');
                    out.write(exportColumns[i]);
                }
                out.write('\n');

                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    if (!cursor.isNull(colIDs[0]))
                        out.write(cursor.getString(colIDs[0]));
                    for (int i = 1; i < exportColumns.length; i++) {
                        out.write(',');
                        if (!cursor.isNull(colIDs[i]))
                            out.write(cursor.getString(colIDs[i]));
                    }
                    out.write('\n');
                }

                out.flush();

            } catch (IOException e) {
                Log.e("ContentProvider", "While writing CSV", e);
            }
        }
    };

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        switch (uriMatcher.match(uri)) {
            case CAPTURE_CSV:
                if (!"r".equals(mode))
                    throw new FileNotFoundException("CSV is read-only");
                return ContentProviderSupport.openPipeHelper(uri, "*/*", null,
                        uri.getLastPathSegment(), csvWriter);
            default:
                return super.openFile(uri, mode);
        }
    }

    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues contentValues) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues contentValues, String s, String[] strings) {
        return 0;
    }
}
