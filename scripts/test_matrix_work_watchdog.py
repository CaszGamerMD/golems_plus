"""Exercise the actual Java watchdog without Minecraft or Gradle dependencies."""
from pathlib import Path
import subprocess
import tempfile

source = Path("src/gametest/java/com/caszgamermd/golemsplus/builder/MatrixWorkWatchdog.java")
test = """
package com.caszgamermd.golemsplus.builder;
class WatchdogTest {
    static void check(long actual,long expected) {
        if(actual!=expected) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        var watch=new MatrixWorkWatchdog(0,0,0);
        for(int tick=1;tick<=1200;tick++) check(watch.sample(tick,0,0),tick);
        check(watch.sample(1201,1,0),0);
        // Losing and repairing the same block is not new progress.
        check(watch.sample(1250,0,0),49);
        check(watch.sample(1300,1,0),99);
        check(watch.sample(2401,1,0),1200);
        check(watch.sample(2402,2,0),0);
        check(watch.sample(2403,2,1),0);
        check(watch.sample(2404,2,0),1);
        check(watch.sample(2405,2,1),2);
    }
}
"""
with tempfile.TemporaryDirectory() as tmp:
    harness = Path(tmp) / "WatchdogTest.java"
    harness.write_text(test)
    subprocess.run(["javac", "-d", tmp, str(source), str(harness)], check=True)
    subprocess.run(["java", "-cp", tmp, "com.caszgamermd.golemsplus.builder.WatchdogTest"], check=True)
print("PASS: no progress times out; new work resets; repair and cursor oscillation do not")
