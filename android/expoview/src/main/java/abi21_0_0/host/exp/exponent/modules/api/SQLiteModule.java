// Copyright 2015-present 650 Industries. All rights reserved.

package abi21_0_0.host.exp.exponent.modules.api;

import abi21_0_0.com.facebook.react.bridge.NativeArray;
import abi21_0_0.com.facebook.react.bridge.Promise;
import abi21_0_0.com.facebook.react.bridge.ReactApplicationContext;
import abi21_0_0.com.facebook.react.bridge.ReactContextBaseJavaModule;
import abi21_0_0.com.facebook.react.bridge.ReactMethod;
import abi21_0_0.com.facebook.react.bridge.ReadableArray;
import abi21_0_0.com.facebook.react.bridge.WritableNativeArray;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import host.exp.exponent.utils.ExpFileUtils;
import host.exp.exponent.utils.ScopedContext;

public class SQLiteModule extends ReactContextBaseJavaModule {
  private static final boolean DEBUG_MODE = false;

  private static final String TAG = SQLiteModule.class.getSimpleName();

  private static final Object[][] EMPTY_ROWS = new Object[][]{};
  private static final String[] EMPTY_COLUMNS = new String[]{};
  private static final SQLitePluginResult EMPTY_RESULT = new SQLitePluginResult(EMPTY_ROWS, EMPTY_COLUMNS, 0, 0, null);

  private static final Map<String, SQLiteDatabase> DATABASES = new HashMap<String, SQLiteDatabase>();

  private ScopedContext mScopedContext;

  public SQLiteModule(ReactApplicationContext reactContext, ScopedContext scopedContext) {
    super(reactContext);
    mScopedContext = scopedContext;
  }

  @Override
  public String getName() {
    return "ExponentSQLite";
  }

  private Handler createBackgroundHandler() {
    HandlerThread thread = new HandlerThread("SQLitePlugin BG Thread");
    thread.start();
    return new Handler(thread.getLooper());
  }

  @ReactMethod
  public void exec(String dbName, ReadableArray queries, Boolean readOnly, Promise promise) {
    try {
      int numQueries = queries.size();
      SQLitePluginResult[] results = new SQLitePluginResult[numQueries];
      SQLiteDatabase db = getDatabase(dbName);

      for (int i = 0; i < numQueries; i++) {
        ReadableArray sqlQuery = queries.getArray(i);
        String sql = sqlQuery.getString(0);
        String[] bindArgs = jsonArrayToStringArray(sqlQuery.getString(1));
        try {
          if (isSelect(sql)) {
            results[i] = doSelectInBackgroundAndPossiblyThrow(sql, bindArgs, db);
          } else { // update/insert/delete
            if (readOnly) {
              results[i] = new SQLitePluginResult(EMPTY_ROWS, EMPTY_COLUMNS, 0, 0, new ReadOnlyException());
            } else {
              results[i] = doUpdateInBackgroundAndPossiblyThrow(sql, bindArgs, db);
            }
          }
        } catch (Throwable e) {
          if (DEBUG_MODE) {
            e.printStackTrace();
          }
          results[i] = new SQLitePluginResult(EMPTY_ROWS, EMPTY_COLUMNS, 0, 0, e);
        }
      }
      NativeArray data = pluginResultsToPrimitiveData(results);
      promise.resolve(data);
    } catch (Exception e) {
      promise.reject("SQLiteError", e);
    }
  }

  // do a update/delete/insert operation
  private SQLitePluginResult doUpdateInBackgroundAndPossiblyThrow(String sql, String[] bindArgs,
                                                                  SQLiteDatabase db) {
    SQLiteStatement statement = null;
    try {
      statement = db.compileStatement(sql);
      if (bindArgs != null) {
        statement.bindAllArgsAsStrings(bindArgs);
      }
      if (isInsert(sql)) {
        long insertId = statement.executeInsert();
        int rowsAffected = insertId >= 0 ? 1 : 0;
        return new SQLitePluginResult(EMPTY_ROWS, EMPTY_COLUMNS, rowsAffected, insertId, null);
      } else if (isDelete(sql) || isUpdate(sql)) {
        int rowsAffected = statement.executeUpdateDelete();
        return new SQLitePluginResult(EMPTY_ROWS, EMPTY_COLUMNS, rowsAffected, 0, null);
      } else {
        // in this case, we don't need rowsAffected or insertId, so we can have a slight
        // perf boost by just executing the query
        statement.execute();
        return EMPTY_RESULT;
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
    }
  }

  // do a select operation
  private SQLitePluginResult doSelectInBackgroundAndPossiblyThrow(String sql, String[] bindArgs,
                                                                  SQLiteDatabase db) {
    Cursor cursor = null;
    try {
      cursor = db.rawQuery(sql, bindArgs);
      int numRows = cursor.getCount();
      if (numRows == 0) {
        return EMPTY_RESULT;
      }
      int numColumns = cursor.getColumnCount();
      Object[][] rows = new Object[numRows][];
      String[] columnNames = cursor.getColumnNames();
      for (int i = 0; cursor.moveToNext(); i++) {
        Object[] row = new Object[numColumns];
        for (int j = 0; j < numColumns; j++) {
          row[j] = getValueFromCursor(cursor, j, cursor.getType(j));
        }
        rows[i] = row;
      }
      return new SQLitePluginResult(rows, columnNames, 0, 0, null);
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  private Object getValueFromCursor(Cursor cursor, int index, int columnType) {
    switch (columnType) {
      case Cursor.FIELD_TYPE_FLOAT:
        return cursor.getFloat(index);
      case Cursor.FIELD_TYPE_INTEGER:
        return cursor.getInt(index);
      case Cursor.FIELD_TYPE_BLOB:
        // convert byte[] to binary string; it's good enough, because
        // WebSQL doesn't support blobs anyway
        return new String(cursor.getBlob(index));
      case Cursor.FIELD_TYPE_STRING:
        return cursor.getString(index);
    }
    return null;
  }

  private SQLiteDatabase getDatabase(String name) throws IOException {
    SQLiteDatabase database = DATABASES.get(name);
    if (database == null) {
      if (":memory:".equals(name)) {
        database = SQLiteDatabase.openOrCreateDatabase(name, null);
      } else {
        File directory = new File(mScopedContext.getFilesDir() + File.separator + "SQLite");
        ExpFileUtils.ensureDirExists(directory);
        database = SQLiteDatabase.openOrCreateDatabase(directory + File.separator + name, null);
      }
      DATABASES.put(name, database);
    }
    return database;
  }

  private static NativeArray pluginResultsToPrimitiveData(SQLitePluginResult[] results) {
    WritableNativeArray list = new WritableNativeArray();
    for (int i = 0; i < results.length; i++) {
      SQLitePluginResult result = results[i];
      WritableNativeArray arr = convertPluginResultToArray(result);
      list.pushArray(arr);
    }
    return list;
  }

  private static WritableNativeArray convertPluginResultToArray(SQLitePluginResult result) {
    WritableNativeArray data = new WritableNativeArray();
    if (result.error != null) {
      data.pushString(result.error.getMessage());
    } else {
      data.pushNull();
    }
    data.pushInt((int) result.insertId);
    data.pushInt(result.rowsAffected);

    // column names
    WritableNativeArray columnNames = new WritableNativeArray();
    for (int i = 0; i < result.columns.length; i++) {
      columnNames.pushString(result.columns[i]);
    }
    data.pushArray(columnNames);

    // rows
    WritableNativeArray rows = new WritableNativeArray();
    for (int i = 0; i < result.rows.length; i++) {
      Object[] values = result.rows[i];
      // row content
      WritableNativeArray rowContent = new WritableNativeArray();
      for (int j = 0; j < values.length; j++) {
        Object value = values[j];
        if (value == null) {
          rowContent.pushNull();
        } else if (value instanceof String) {
          rowContent.pushString((String)value);
        } else if (value instanceof Boolean) {
          rowContent.pushBoolean((Boolean)value);
        } else {
          Number v = (Number)value;
          rowContent.pushDouble(v.doubleValue());
        }
      }
      rows.pushArray(rowContent);
    }
    data.pushArray(rows);
    return data;
  }

  private static boolean isSelect(String str) {
    return startsWithCaseInsensitive(str, "select");
  }
  private static boolean isInsert(String str) {
    return startsWithCaseInsensitive(str, "insert");
  }
  private static boolean isUpdate(String str) {
    return startsWithCaseInsensitive(str, "update");
  }
  private static boolean isDelete(String str) {
    return startsWithCaseInsensitive(str, "delete");
  }

  // identify an "insert"/"select" query more efficiently than with a Pattern
  private static boolean startsWithCaseInsensitive(String str, String substr) {
    int i = -1;
    int len = str.length();
    while (++i < len) {
      char ch = str.charAt(i);
      if (!Character.isWhitespace(ch)) {
        break;
      }
    }

    int j = -1;
    int substrLen = substr.length();
    while (++j < substrLen) {
      if (j + i >= len) {
        return false;
      }
      char ch = str.charAt(j + i);
      if (Character.toLowerCase(ch) != substr.charAt(j)) {
        return false;
      }
    }
    return true;
  }

  private static String[] jsonArrayToStringArray(String jsonArray) throws JSONException {
    JSONArray array = new JSONArray(jsonArray);
    int len = array.length();
    String[] res = new String[len];
    for (int i = 0; i < len; i++) {
      res[i] = array.getString(i);
    }
    return res;
  }

  private static class SQLitePluginResult {
    public final Object[][] rows;
    public final String[] columns;
    public final int rowsAffected;
    public final long insertId;
    public final Throwable error;

    public SQLitePluginResult(Object[][] rows, String[] columns,
                              int rowsAffected, long insertId, Throwable error) {
      this.rows = rows;
      this.columns = columns;
      this.rowsAffected = rowsAffected;
      this.insertId = insertId;
      this.error = error;
    }
  }

  private static class ReadOnlyException extends Exception {
    public ReadOnlyException() {
      super("could not prepare statement (23 not authorized)");
    }
  }
}
