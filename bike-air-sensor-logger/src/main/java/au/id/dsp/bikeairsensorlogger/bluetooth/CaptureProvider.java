package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.annotation.Nullable;

/**
 * Created by damien on 11/10/15.
 */
public class CaptureProvider extends ContentProvider {

    public static final String AUTHORITY = "au.id.dsp.bikeairsensorlogger.bluetooth.CaptureProvider";
    public static final int CAPTURES = 100;
    public static final int CAPTURE = 110;
    public static final int CAPTURES_WITH_COUNTS = 120;
    public static final Uri CAPTURES_WITH_COUNTS_URI = Uri.parse("content://" + AUTHORITY + "/captureswithcounts");

    public static final String CONTENT_ITEM_TYPE = ContentResolver.CURSOR_ITEM_BASE_TYPE + "/captures";
    public static final String CONTENT_TYPE = ContentResolver.CURSOR_DIR_BASE_TYPE + "/captures";

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        uriMatcher.addURI(AUTHORITY, "captures", CAPTURES);
        uriMatcher.addURI(AUTHORITY, "captures/#", CAPTURE);
        uriMatcher.addURI(AUTHORITY, "captureswithcounts", CAPTURES_WITH_COUNTS);
    }

    private LogDatabase db;

    @Override
    public int delete(Uri uri, String s, String[] strings) {
        return 0;
    }

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
            default:
                throw new RuntimeException("Unknown URI " + uri);
        }

        Cursor cursor = builder.query(db.db, projection, selection, selectArgs, null, null, sortOrder);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        return null;
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
