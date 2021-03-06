package org.mwdb.task;

import org.mwdb.KCallback;
import org.mwdb.plugin.KScheduler;

public class NoopScheduler implements KScheduler {

    @Override
    public void dispatch(KCallback task) {
        task.on(null);
    }

    @Override
    public void start() {
        //noop
    }

    @Override
    public void stop() {
        //noop
    }

}
