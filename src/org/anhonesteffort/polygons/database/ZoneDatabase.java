package org.anhonesteffort.polygons.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.util.Log;

import org.anhonesteffort.polygons.database.model.PointRecord;
import org.anhonesteffort.polygons.database.model.ZoneRecord;

import java.util.ArrayList;

public class ZoneDatabase {

  private static final String TAG = "ZoneDatabase";

  private static final String SELECTION_ZONE       = "SELECT zone._id, zone.label FROM zone ";

  private static final String SELECTION_POINT      = "SELECT p_id, zone_id, longitude, latitude " +
                                                     "FROM point ";

  private static final String SELECTION_ZONE_POINT = "SELECT zone._id, zone.label, point.p_id, point.zone_id, point.longitude, point.latitude " +
                                                     "FROM zone JOIN " +
                                                       "(SELECT p_id, zone_id, longitude, latitude " +
                                                        "FROM point " +
                                                        "ORDER BY p_order) AS point " +
                                                     "ON zone._id = point.zone_id ";

  private static final String SELECTION_OCCUPY     = "SELECT zone._id, zone.label " +
                                                     "FROM zone JOIN occupy " +
                                                     "ON zone._id = occupy.zone_id ";

  private static final String SELECTION_SELECTED   = "SELECT zone._id, zone.label " +
                                                     "FROM zone JOIN selected " +
                                                     "ON zone._id = selected.zone_id ";

  private DatabaseHelper dbHelper;
  private ArrayList<GeometryChangeListener> listeners = new ArrayList<GeometryChangeListener>();

  protected ZoneDatabase(DatabaseHelper dbHelper) {
    this.dbHelper = dbHelper;
    if(isInitialized() == false) {
      initialize();
      Log.d(TAG, "Initialized the zone tables.");
    }
  }

  private void initialize() {
    dbHelper.exec("SELECT InitSpatialMetaData()");
    dbHelper.exec("DROP TABLE IF EXISTS zone");
    dbHelper.exec("DROP TABLE IF EXISTS point");
    dbHelper.exec("DROP TABLE IF EXISTS occupy");
    dbHelper.exec("DROP TABLE IF EXISTS selected");

    dbHelper.exec("CREATE TABLE IF NOT EXISTS zone (" +
                    "_id INTEGER NOT NULL PRIMARY KEY, " +
                    "label VARCHAR(100) NOT NULL" +
                  ")");
    dbHelper.exec("SELECT AddGeometryColumn('zone', 'geometry', 4326, 'GEOMETRY')");

    dbHelper.exec("CREATE TABLE IF NOT EXISTS point (" +
                    "p_id INTEGER NOT NULL PRIMARY KEY, " +
                    "zone_id INTEGER NOT NULL, " +
                    "p_order INTEGER NOT NULL, " +
                    "latitude REAL NOT NULL, " +
                    "longitude REAL NOT NULL, " +
                    "FOREIGN KEY (zone_id) REFERENCES zone(_id) ON DELETE CASCADE" +
                  ")");

    dbHelper.exec("CREATE TABLE IF NOT EXISTS occupy (" +
                    "zone_id INTEGER NOT NULL, " +
                    "PRIMARY KEY (zone_id), " +
                    "FOREIGN KEY (zone_id) REFERENCES zone(_id) ON DELETE CASCADE" +
                  ")");

    dbHelper.exec("CREATE TABLE IF NOT EXISTS selected (" +
                    "zone_id INTEGER NOT NULL, " +
                    "PRIMARY KEY (zone_id), " +
                    "FOREIGN KEY (zone_id) REFERENCES zone(_id) ON DELETE CASCADE" +
                  ")");
  }

  private boolean isInitialized() {
    try {

      SpatialCursor zonesQuery = dbHelper.prepare("SELECT 1 FROM zone");
      zonesQuery.close();
      return true;

    } catch (SQLException e) {
      dbHelper.displayException(e);
    }

    return false;
  }

  public void addGeometryChangeListener(GeometryChangeListener listener) {
    listeners.add(listener);
  }

  public void removeGeometryChangeListener(GeometryChangeListener listener) {
    listeners.remove(listener);
  }

  public boolean isLabelAvailable(String label) {
    SpatialCursor zoneRecords = dbHelper.prepare(SELECTION_ZONE + "WHERE zone.label = '" +
                                                    DatabaseHelper.escapeString(label) + "'");
    if(zoneRecords.getCount() > 0) {
      zoneRecords.close();
      return false;
    }

    zoneRecords.close();
    return true;
  }

  private boolean zoneExists(int zone_id) {
    SpatialCursor zoneRecords = dbHelper.prepare(SELECTION_ZONE + "WHERE zone._id = '" + zone_id + "'");

    if(zoneRecords.moveToNext()) {
      zoneRecords.close();
      return true;
    }

    zoneRecords.close();
    return false;
  }

  private void geometryChange() {
    for(GeometryChangeListener listener : listeners) {
      if(listener != null)
        listener.onGeometryChange();
    }
  }

  public PointRecord addPoint(PointRecord point, int zone_id) {
    ContentValues values = new ContentValues();
    values.put("zone_id", zone_id);
    values.put("p_order", -1);
    values.put("latitude", point.getY());
    values.put("longitude", point.getX());
    dbHelper.insert("point", values);

    PointRecord retPoint = null;
    SpatialCursor pointRecords = dbHelper.prepare(SELECTION_POINT + "ORDER BY p_id DESC LIMIT 1");
    if(pointRecords.moveToNext())
      retPoint = new PointRecord(pointRecords.getInt(0), zone_id, point.getX(), point.getY());
    pointRecords.close();

    return retPoint;
  }

  public PointRecord getPoint(int point_id) {
    PointRecord outPoint = null;
    SpatialCursor pointRecords = dbHelper.prepare(SELECTION_POINT + "WHERE p_id = '" + point_id + "'");

    if(pointRecords.moveToNext())
      outPoint = new PointRecord(pointRecords.getInt(0),
                                 pointRecords.getInt(1),
                                 pointRecords.getDouble(2),
                                 pointRecords.getDouble(3));

    pointRecords.close();
    return outPoint;
  }

  public ZoneRecord addZone(String label) {
    Log.d(TAG, "addZone() " + label);
    ZoneRecord outZone = null;

    ContentValues values = new ContentValues();
    values.put("label", DatabaseHelper.escapeString(label));
    values.put("geometry", "ST_GeomFromText('POINT(0.0 0.0)', 4326)");
    dbHelper.insert("zone", values);

    SpatialCursor zoneRecords = dbHelper.prepare(SELECTION_ZONE + "ORDER BY _id DESC LIMIT 1");
    if(zoneRecords.moveToNext()) {
      outZone = new ZoneRecord(zoneRecords.getInt(0), label);
      dbHelper.getActionDatabase().initZoneActions(outZone.getId());
    }

    zoneRecords.close();
    return outZone;
  }

  public void deleteZone(int zone_id) {
    Log.d(TAG, "deleteZone(), id: " + zone_id);
    dbHelper.exec("DELETE FROM zone WHERE _id = '" + zone_id + "'");
  }

  public void updateZone(ZoneRecord zone) {
    Log.d(TAG, "updateZone(), id: " + zone.getId());

    if (zoneExists(zone.getId())) {

      if (zone.getPoints().isEmpty() == false) {
        String sql = "UPDATE zone SET geometry = GeomFromText('POLYGON((";
        for (int i = 0; i < zone.getPoints().size(); i++) {
          if (i != 0)
            sql += ",";
          sql += zone.getPoints().get(i).getX() + " " + zone.getPoints().get(i).getY();
        }
        sql += "," + zone.getPoints().get(0).getX() + " " + zone.getPoints().get(0).getY();
        dbHelper.exec(sql + "))', 4326) WHERE _id = '" + zone.getId() + "'");

        sql = "DELETE FROM point WHERE p_id NOT IN (";
        for(PointRecord point : zone.getPoints())
          sql += "'" + point.getId() + "', ";
        dbHelper.exec(sql + "'-2') AND zone_id = '" + zone.getId() + "'");

        for (int i = 0; i < zone.getPoints().size(); i++)
          dbHelper.exec("UPDATE point SET p_order = '" + i + "', " +
              "longitude = '" + zone.getPoints().get(i).getX() + "', " +
              "latitude = '" + zone.getPoints().get(i).getY() + "' " +
              "WHERE p_id = '" + zone.getPoints().get(i).getId() + "'");
      }

      dbHelper.exec("UPDATE zone SET label = '" + DatabaseHelper.escapeString(zone.getLabel()) + "' " +
                    "WHERE _id = '" + zone.getId() + "'");
    }
    else
      zone = null;

    geometryChange();
  }

  public ZoneRecord getZone(int zone_id) {
    SpatialCursor zoneRecords = dbHelper.prepare(SELECTION_ZONE_POINT + "WHERE zone._id = '" + zone_id + "'");
    Reader zoneReader = new Reader(zoneRecords);
    ZoneRecord zone = zoneReader.getNext();

    zoneReader.close();
    return zone;
  }

  public Cursor getZones() {
    return dbHelper.prepare(SELECTION_ZONE);
  }

  public Cursor getZonesContainingPoint(PointRecord point) {
    return dbHelper.prepare(SELECTION_ZONE_POINT + "WHERE " +
                                                   "ST_WITHIN(GeomFromText('" +
                                                     "POINT(" + point.getX() + " " + point.getY() + ")'" +
                                                     ", 4326), zone.geometry) " +
                                                   "ORDER BY ST_AREA(zone.geometry) ASC");
  }

  public Cursor getZonesIntersecting(ZoneRecord zone) {
    String sql = SELECTION_ZONE_POINT + "WHERE ST_Intersects(zone.geometry, GeomFromText('POLYGON((";

    for(int i = 0; i < zone.getPoints().size(); i++) {
      if(i != 0)
        sql += ",";
      sql += zone.getPoints().get(i).getX() + " " + zone.getPoints().get(i).getY();
    }
    sql += "," + zone.getPoints().get(0).getX() + " " + zone.getPoints().get(0).getY();

    return dbHelper.prepare(sql + "))', 4326))" + "ORDER BY ST_AREA(zone.geometry) DESC");
  }

  public PointRecord[] getZoneBounds(int zone_id) {
    PointRecord[] pointBounds = {null, null};
    SpatialCursor zoneRecords = dbHelper.prepare("SELECT " +
                                                   "MbrMinX(geometry), " +
                                                   "MbrMinY(geometry), " +
                                                   "MbrMaxX(geometry), " +
                                                   "MbrMaxY(geometry) " +
                                                 "FROM zone WHERE zone._id = '" + zone_id + "'");
    if(zoneRecords.moveToNext()) {
      pointBounds[0] = new PointRecord(-1, -1, zoneRecords.getDouble(0), zoneRecords.getDouble(1));
      pointBounds[1] = new PointRecord(-1, -1, zoneRecords.getDouble(2), zoneRecords.getDouble(3));
    }

    zoneRecords.close();
    return pointBounds;
  }

  public double distanceBetween(PointRecord point, int zone_id) {
    double distance = -1;
    SpatialCursor zoneRecords = dbHelper.prepare("SELECT " +
                                                   "Distance(" +
                                                   "GeomFromText('POINT(" + point.getX() + " " + point.getY() + ")', 4326), " +
                                                     "geometry, " +
                                                     "0" +
                                                   ") AS dist " +
                                                 "FROM zone " +
                                                 "WHERE zone._id = '" + zone_id + "' AND dist > 0.0 " +
                                                 "ORDER BY dist ASC LIMIT 1");
    if(zoneRecords.moveToNext())
      distance = zoneRecords.getDouble(0);

    zoneRecords.close();
    return distance;
  }

  public double distanceToClosestZone(PointRecord point) {
    double distance = -1;
    SpatialCursor zoneRecords = dbHelper.prepare("SELECT " +
                                                 "Distance(" +
                                                   "GeomFromText('" +
                                                     "POINT(" +
                                                       point.getX() + " " +
                                                       point.getY() +
                                                     ")', " +
                                                   "4326), " +
                                                   "geometry, " +
                                                   "0" +
                                                 ") AS dist " +
                                                 "FROM zone " +
                                                 "WHERE dist > 0.0 " +
                                                 "ORDER BY dist ASC LIMIT 1");
    if(zoneRecords.moveToNext())
      distance = zoneRecords.getDouble(0);

    zoneRecords.close();
    return distance;
  }

  public void setZoneOccupancy(int zone_id, boolean occupy) {
    Log.d(TAG, "setZoneOccupancy(), id: " + zone_id + ", occupancy: " + occupy);

    if(occupy) {
      ContentValues values = new ContentValues();
      values.put("zone_id", zone_id);
      dbHelper.insert("occupy", values);
    }
    else
      dbHelper.exec("DELETE FROM occupy where zone_id = '" + zone_id + "'");
  }

  public Cursor getZonesOccupied() {
    return dbHelper.prepare(SELECTION_OCCUPY);
  }

  public void setZoneSelected(int zone_id, boolean selected) {
    if(selected) {
      ContentValues values = new ContentValues();
      values.put("zone_id", zone_id);
      dbHelper.insert("selected", values);
    }
    else
      dbHelper.exec("DELETE FROM selected where zone_id = '" + zone_id + "'");
  }

  public void clearSelectedZones() {
    dbHelper.exec("DELETE FROM selected");
  }

  public boolean isZoneSelected(int zone_id) {
    SpatialCursor zoneRecords = dbHelper.prepare(SELECTION_SELECTED + "WHERE selected.zone_id = '" + zone_id + "'");

    if(zoneRecords.getCount() > 0) {
      zoneRecords.close();
      return true;
    }

    zoneRecords.close();
    return false;
  }

  public Cursor getZonesSelected() {
    return dbHelper.prepare(SELECTION_SELECTED);
  }

  public static class Reader {

    private Cursor cursor;
    private ZoneRecord currentZone = null;

    private boolean has_points = false;
    private int zone_start_pos = 0;

    public Reader(Cursor cursor) {
      this.cursor = cursor;

      if(cursor.getColumnIndex("p_id") != -1 || cursor.getColumnIndex("point.p_id") != -1)
        has_points = true;
    }

    public ZoneRecord getNext() {
      if (cursor == null || !cursor.moveToNext() || cursor.isAfterLast())
        return null;

      currentZone = null;
      return getCurrent();
    }

    public ZoneRecord getCurrent() {
      if (currentZone != null)
        return currentZone;

      if (has_points)
        return getZoneWithPoints();

      return getZone();
    }

    private ZoneRecord addPoints(ZoneRecord newZone) {
      newZone.getPoints().add(new PointRecord(cursor.getInt(2), cursor.getInt(3), cursor.getDouble(4), cursor.getDouble(5)));
      while(cursor.moveToNext()) {
        if (cursor.getInt(0) != newZone.getId())
          break;
        newZone.getPoints().add(new PointRecord(cursor.getInt(2), cursor.getInt(3), cursor.getDouble(4), cursor.getDouble(5)));
      }

      zone_start_pos = cursor.getPosition();
      return newZone;
    }

    private ZoneRecord getZoneWithPoints() {
      if (cursor.getPosition() != zone_start_pos)
        cursor.moveToPosition(zone_start_pos);

      currentZone = new ZoneRecord(cursor.getInt(0), cursor.getString(1));
      return addPoints(currentZone);
    }

    private ZoneRecord getZone() {
      currentZone = new ZoneRecord(cursor.getInt(0), cursor.getString(1));
      return currentZone;
    }

    public void close() {
      cursor.close();
    }
  }

}
