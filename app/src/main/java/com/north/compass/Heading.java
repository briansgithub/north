package com.north.compass;

final class Heading {
    private Heading() {}

    /** Device-to-world matrix. Screen axes come from {@link #project}. */
    static float[] fromRotationVector(float[] values) {
        float[] rotation = new float[9];
        float[] copy = new float[values.length];
        System.arraycopy(values, 0, copy, 0, values.length);
        android.hardware.SensorManager.getRotationMatrixFromVector(rotation, copy);
        return rotation;
    }

    /**
     * Clockwise screen angle of a world vector (east, north, up) for someone looking at the front of the phone.
     * Screen right and screen up follow the current display rotation.
     */
    static float project(float[] deviceToWorld, int displayRotation, float east, float north, float up) {
        float[] screen = screenMatrix(deviceToWorld, displayRotation);
        if (screen == null) {
            return Float.NaN;
        }
        float right = screen[0] * east + screen[3] * north + screen[6] * up;
        float screenUp = screen[1] * east + screen[4] * north + screen[7] * up;
        if (right * right + screenUp * screenUp < 0.02f) {
            return Float.NaN;
        }
        float degrees = (float) Math.toDegrees(Math.atan2(right, screenUp));
        if (degrees < 0f) {
            degrees += 360f;
        }
        return degrees;
    }

    private static float[] screenMatrix(float[] deviceToWorld, int displayRotation) {
        if (deviceToWorld == null) {
            return null;
        }
        int axisX;
        int axisY;
        switch (displayRotation) {
            case android.view.Surface.ROTATION_90:
                axisX = android.hardware.SensorManager.AXIS_Y;
                axisY = android.hardware.SensorManager.AXIS_MINUS_X;
                break;
            case android.view.Surface.ROTATION_180:
                axisX = android.hardware.SensorManager.AXIS_MINUS_X;
                axisY = android.hardware.SensorManager.AXIS_MINUS_Y;
                break;
            case android.view.Surface.ROTATION_270:
                axisX = android.hardware.SensorManager.AXIS_MINUS_Y;
                axisY = android.hardware.SensorManager.AXIS_X;
                break;
            default:
                axisX = android.hardware.SensorManager.AXIS_X;
                axisY = android.hardware.SensorManager.AXIS_Y;
                break;
        }
        float[] screen = new float[9];
        if (!android.hardware.SensorManager.remapCoordinateSystem(deviceToWorld, axisX, axisY, screen)) {
            return null;
        }
        return screen;
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

    static float arrowRotation(float[] deviceToWorld, int displayRotation, String mode, float declinationEast, float uptownTrue) {
        float bearing = 0f;
        if (!WidgetStore.MAGNETIC.equals(mode)) {
            bearing = -declinationEast;
            if (WidgetStore.GRID.equals(mode) && !Float.isNaN(uptownTrue)) {
                bearing += uptownTrue;
            }
        }
        double radians = Math.toRadians(bearing);
        return project(deviceToWorld, displayRotation, (float) Math.sin(radians), (float) Math.cos(radians), 0f);
    }
}
