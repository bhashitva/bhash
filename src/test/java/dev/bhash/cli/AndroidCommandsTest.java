// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidCommandsTest {
    private static final String APP = "org.example.hinditasks";
    private static final String ACTIVITY = "org.bhashitva.tasks.MainActivity";
    private static final String COMPONENT = APP + "/" + ACTIVITY;
    private static final String WINDOW = "Window{79f320 u0 " + COMPONENT + "}";
    private static final String LAUNCHER = "Window{62ab11 u0 com.android.launcher3/.Launcher}";

    @Test
    void confirmsTheFocusedDrawnActivityWithEitherComponentSpelling() {
        assertTrue(AndroidCommands.hasDrawnFocusedActivity(dump(WINDOW, "HAS_DRAWN"), APP, ACTIVITY));
        String shortWindow = WINDOW.replace(COMPONENT, "org.bhashitva.tasks/.MainActivity");
        String modernDump = dump(WINDOW, "HAS_DRAWN").replace(WINDOW, shortWindow)
            .replace("mDrawState=HAS_DRAWN", "Surface: shown=true  mDrawState=HAS_DRAWN");
        assertTrue(AndroidCommands.hasDrawnFocusedActivity(modernDump, "org.bhashitva.tasks", ACTIVITY));
    }

    @Test
    void rejectsPendingDrawingAndUnfocusedOrUnrelatedWindows() {
        assertFalse(AndroidCommands.hasDrawnFocusedActivity(dump(WINDOW, "DRAW_PENDING"), APP, ACTIVITY));
        assertFalse(AndroidCommands.hasDrawnFocusedActivity(dump(LAUNCHER, "HAS_DRAWN"), APP, ACTIVITY));
        String splash = WINDOW.replace(COMPONENT, "Splash Screen " + APP);
        assertFalse(AndroidCommands.hasDrawnFocusedActivity(dump(splash, "HAS_DRAWN"), APP, ACTIVITY));
        String anotherActivity = WINDOW.replace("MainActivity", "MainActivitySettings");
        assertFalse(AndroidCommands.hasDrawnFocusedActivity(dump(anotherActivity, "HAS_DRAWN"), APP, ACTIVITY));
        String staleWindow = WINDOW.replace("79f320", "a84c30");
        assertFalse(AndroidCommands.hasDrawnFocusedActivity(dump(staleWindow, "HAS_DRAWN"), APP, ACTIVITY));
    }

    private static String dump(String focused, String appDrawState) {
        return """
            WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
              mCurrentFocus=%s
            WINDOW MANAGER WINDOWS (dumpsys window windows)
              Window #0 %s:
                mDrawState=%s mLastHidden=false
              Window #1 %s:
                mDrawState=HAS_DRAWN mLastHidden=false
            """.formatted(focused, WINDOW, appDrawState, LAUNCHER);
    }
}
