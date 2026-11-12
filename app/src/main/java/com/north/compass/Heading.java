package com.north.compass;

final class Heading {
    private Heading() {}

    static float fromRotationVector(float[] values) {
        float[] rotation = new float[9];
        float[] copy = new float[values.length];
        System.arraycopy(values, 0, copy, 0, values.length);
        android.hardware.SensorManager.getRotationMatrixFromVector(rotation, copy);
        float east = rotation[1];
        float north = rotation[4];
        if (east * east + north * north < 0.02f) {
            return Float.NaN;
        }
        float heading = (float) Math.toDegrees(Math.atan2(east, north));
        if (heading < 0) {
            heading += 360f;
        }
        return heading;
    }

    static float smooth(float previous, float heading) {
        if (Float.isNaN(previous)) {
            return heading;
        }
        float delta = heading - previous;
        while (delta > 180f) {
            delta -= 360f;
        }
        while (delta < -180f) {
            delta += 360f;
        }
        return (previous + 0.25f * delta + 360f) % 360f;
    }

    static float arrowRotation(float heading, String mode, float declinationEast, float uptownTrue) {
        float target = 0f;
        if (!WidgetStore.MAGNETIC.equals(mode)) {
            target = -declinationEast;
            if (WidgetStore.GRID.equals(mode) && !Float.isNaN(uptownTrue)) {
                target += uptownTrue;
            }
        }
        return wrap(target - heading);
    }

    static float wrap(float degrees) {
        float value = degrees % 360f;
        if (value < 0) {
            value += 360f;
        }
        return value;
    }
}
