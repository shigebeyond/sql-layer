SELECT pid FROM places
 WHERE GEO_COVERS(GEO_WKB(shape), GEO_LAT_LON(42.3583, -71.0603))
