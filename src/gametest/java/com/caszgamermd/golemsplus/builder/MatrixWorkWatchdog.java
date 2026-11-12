package com.caszgamermd.golemsplus.builder;

/** Movement, claim churn and repeated repairs cannot reset completed-work progress. */
final class MatrixWorkWatchdog {
    private int bestWork, bestClearing;
    private long lastProgress;
    MatrixWorkWatchdog(long tick, int work, int clearing) {
        lastProgress=tick;
        bestWork=work;
        bestClearing=clearing;
    }
    long sample(long tick, int work, int clearing) {
        if(work>bestWork || clearing>bestClearing) lastProgress=tick;
        bestWork=Math.max(bestWork,work);
        bestClearing=Math.max(bestClearing,clearing);
        return tick-lastProgress;
    }
}
