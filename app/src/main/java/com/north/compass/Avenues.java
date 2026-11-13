package com.north.compass;

/**
 * Uptown bearings of avenue centerlines. Endpoints are street intersections.
 * The Fifth Avenue start and 59th Street corner are the points Charles Petzold
 * published in 2005; the rest are the same corners from a street geocoder.
 * On the 1811 grid the bearing is about 29 degrees. It changes above 155th
 * Street and below 14th Street. The result is that street's whole-degree
 * uptown bearing. At an intersection the more northward street wins.
 */
final class Avenues {
    private static final double LIMIT_MILES = 0.5;
    private static final double INTERSECTION_MILES = 0.02;
    private static final double TRACK_MILES = 1.0;
    private static final double CACHE_MILES = 0.01;
    private static final double EARTH_MILES = 3958.8;

    private static boolean cacheValid;
    private static double cacheLat;
    private static double cacheLon;
    private static float cacheBearing = Float.NaN;
    private static double cacheMiles = Double.POSITIVE_INFINITY;

    private static final Segment[][] SEGMENTS = {
            chain(40.73140, -73.99698, 40.76426, -73.97303, 40.796953, -73.949448, 40.814720, -73.936205),
            chain(40.739766, -74.002518, 40.766734, -73.982874, 40.800494, -73.958460, 40.810173, -73.951167, 40.829174, -73.937293),
            chain(40.731353, -73.982565, 40.759637, -73.961983, 40.792190, -73.938202, 40.801835, -73.931403),
            chain(40.748878, -74.006991, 40.760807, -73.998366, 40.770335, -73.991404),
            chain(40.784388, -73.981255, 40.801646, -73.968575),
            chain(40.704537, -74.014235, 40.725492, -73.996724, 40.734803, -73.990772),
            chain(40.830886, -73.941371, 40.849338, -73.933772),
            chain(40.832818, -73.945932, 40.850172, -73.935715, 40.865514, -73.927300, 40.871179, -73.914041),
    };

    private Avenues() {}

    /** Whole-degree true bearing of the nearest uptown street, or NaN outside Manhattan. */
    static float uptownTrue(double lat, double lon) {
        ensure(lat, lon);
        return cacheBearing;
    }

    /** Close GPS is useful only inside or just beside the mapped streets. */
    static boolean trackClosely(double lat, double lon) {
        ensure(lat, lon);
        return cacheMiles <= TRACK_MILES;
    }

    private static void ensure(double lat, double lon) {
        if (cacheValid && miles(cacheLat, cacheLon, lat, lon) < CACHE_MILES) {
            return;
        }
        double best = Double.POSITIVE_INFINITY;
        Segment nearest = null;
        for (Segment[] chain : SEGMENTS) {
            for (Segment segment : chain) {
                double miles = segment.milesFrom(lat, lon);
                if (miles < best) {
                    best = miles;
                    nearest = segment;
                }
            }
        }
        float bearing = Float.NaN;
        if (nearest != null && best <= LIMIT_MILES) {
            Segment chosen = nearest;
            double northward = angleFromNorth(nearest.uptown);
            double window = best + INTERSECTION_MILES;
            for (Segment[] chain : SEGMENTS) {
                for (Segment segment : chain) {
                    if (segment.milesFrom(lat, lon) > window) {
                        continue;
                    }
                    double candidate = angleFromNorth(segment.uptown);
                    if (candidate + 0.5 < northward) {
                        chosen = segment;
                        northward = candidate;
                    }
                }
            }
            int degrees = (int) Math.round(chosen.uptown) % 360;
            if (degrees < 0) {
                degrees += 360;
            }
            bearing = degrees;
        }
        cacheLat = lat;
        cacheLon = lon;
        cacheBearing = bearing;
        cacheMiles = best;
        cacheValid = true;
    }

    private static double angleFromNorth(double bearing) {
        double wrapped = bearing % 360.0;
        if (wrapped < 0) {
            wrapped += 360.0;
        }
        return Math.min(wrapped, 360.0 - wrapped);
    }

    private static double miles(double lat1, double lon1, double lat2, double lon2) {
        double scale = Math.cos(Math.toRadians(lat1)) * EARTH_MILES * Math.PI / 180.0;
        double north = EARTH_MILES * Math.PI / 180.0;
        double dx = (lon2 - lon1) * scale;
        double dy = (lat2 - lat1) * north;
        return Math.hypot(dx, dy);
    }

    private static Segment[] chain(double... latLon) {
        Segment[] segments = new Segment[latLon.length / 2 - 1];
        for (int i = 0; i < segments.length; i++) {
            int at = i * 2;
            segments[i] = new Segment(latLon[at], latLon[at + 1], latLon[at + 2], latLon[at + 3]);
        }
        return segments;
    }

    private static final class Segment {
        final double lat1;
        final double lon1;
        final double lat2;
        final double lon2;
        final double uptown;

        Segment(double lat1, double lon1, double lat2, double lon2) {
            this.lat1 = lat1;
            this.lon1 = lon1;
            this.lat2 = lat2;
            this.lon2 = lon2;
            this.uptown = bearing(lat1, lon1, lat2, lon2);
        }

        double milesFrom(double lat, double lon) {
            double scale = Math.cos(Math.toRadians(lat)) * EARTH_MILES * Math.PI / 180.0;
            double north = EARTH_MILES * Math.PI / 180.0;
            double ax = (lon1 - lon) * scale;
            double ay = (lat1 - lat) * north;
            double bx = (lon2 - lon) * scale;
            double by = (lat2 - lat) * north;
            double abx = bx - ax;
            double aby = by - ay;
            double length = abx * abx + aby * aby;
            double t = length == 0 ? 0 : -(ax * abx + ay * aby) / length;
            if (t < 0) {
                t = 0;
            } else if (t > 1) {
                t = 1;
            }
            double dx = ax + t * abx;
            double dy = ay + t * aby;
            return Math.hypot(dx, dy);
        }
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dlon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dlon) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dlon);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        if (degrees < 0) {
            degrees += 360.0;
        }
        return degrees;
    }
}
