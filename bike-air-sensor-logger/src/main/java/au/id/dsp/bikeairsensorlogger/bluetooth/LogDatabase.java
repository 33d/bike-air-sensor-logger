package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

import java.io.Closeable;
import java.io.IOException;
import java.util.AbstractList;
import java.util.Date;
import java.util.Iterator;

/**
 * Created by damien on 10/10/15.
 */
public class LogDatabase implements Closeable {

    private class DBHelper extends SQLiteOpenHelper {
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "logs.db";

        public DBHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE CAPTURE ("
                + BaseColumns._ID + " INTEGER PRIMARY KEY, "
                + "START INTEGER, "
                + "END INTEGER, "
                + "ADDRESS TEXT, "
                + "NAME TEXT"
            + ");");
            db.execSQL("CREATE TABLE LOG ("
                + BaseColumns._ID + " INTEGER PRIMARY KEY, "
                + "CAPTURE INTEGER REFERENCES CAPTURE(" + BaseColumns._ID + ") ON DELETE CASCADE, "
                + "LATITUDE REAL, "
                + "LONGITUDE REAL, "
                + "ACCURACY REAL, "
                + "TIME INTEGER, "
                + "TEXT TEXT"
            + ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {

        }
    }

    public class Captures implements Iterable<Capture> {
        public Capture get(int i) {
            return null;
        }

        public int size() {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM CAPTURE", new String[0]);
            return c.getCount();
        }

        @Override
        public Iterator<Capture> iterator() {
            final Cursor c = db.rawQuery("SELECT " + BaseColumns._ID + ", ADDRESS, NAME, START, END FROM CAPTURE", new String[0]);
            final int idIndex = c.getColumnIndexOrThrow(BaseColumns._ID);
            return new Iterator<Capture>() {
                @Override
                public boolean hasNext() {
                    return !c.isLast();
                }

                @Override
                public Capture next() {
                    c.moveToNext();
                    Date end = c.isNull(4) ? null : new Date(c.getLong(4));
                    return new Capture(c.getLong(0), c.getString(1), c.getString(2), new Date(c.getLong(3)), end);
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    public class Capture {
        protected final long key;
        protected final String address;
        protected final String name;
        protected final Date start;
        protected final Date end;

        private Capture(long key, String address, String name, Date start, Date end) {
            this.address = address;
            this.key = key;
            this.name = name;
            this.start = start;
            this.end = end;
        }

        public int size() {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM LOG WHERE CAPTURE=?",
                    new String[] { Long.toString(key) });
            return c.getCount();
        }

        public String getAddress() {
            return address;
        }

        public Date getEnd() {
            return end;
        }

        public long getKey() {
            return key;
        }

        public String getName() {
            return name;
        }

        public Date getStart() {
            return start;
        }
    }

    public class WritableCapture extends Capture implements Closeable {
        private WritableCapture(long key, String address, String name, Date start, Date end) {
            super(key, address, name, start, end);
        }

        @Override
        public void close() throws IOException {
            ContentValues values = new ContentValues();
            values.put("END", System.currentTimeMillis());
            db.update("CAPTURE", values, BaseColumns._ID + "=?", new String[]{Long.toString(key)});
        }

        public void write(double latitude, double longitude, double accuracy, long time, String text) {
            ContentValues values = new ContentValues();
            values.put("CAPTURE", key);
            values.put("LATITUDE", latitude);
            values.put("LONGITUDE", longitude);
            values.put("ACCURACY", accuracy);
            values.put("TIME", time);
            values.put("TEXT", text);
            db.insertOrThrow("LOG", null, values);
        }
    }

    private final SQLiteDatabase db;

    public LogDatabase(Context context) {
        db = new DBHelper(context).getWritableDatabase();
    }

    public WritableCapture createCapture(String address, String name) {
        long start = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("ADDRESS", address);
        values.put("NAME", name);
        values.put("START", start);
        long id = db.insertOrThrow("CAPTURE", null, values);
        return new WritableCapture(id, address, name, new Date(start), null);
    }

    public Captures getCaptures() {
        return new Captures();
    }

    @Override
    public void close() throws IOException {
        db.close();
    }
}