package org.benf.cfr.reader.util.output;

import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.entities.Method;

public class ZipSummaryDumper implements SummaryDumper {
    private final StringBuilder sharedBuffer;

    private transient JavaTypeInstance lastControllingType = null;
    private transient Method lastMethod = null;

    ZipSummaryDumper(StringBuilder sharedBuffer) {
        this.sharedBuffer = sharedBuffer;
    }

    @Override
    public void notify(String message) {
        sharedBuffer.append(message).append("\n");
    }

    @Override
    public void notifyError(JavaTypeInstance controllingType, Method method, String error) {
        if (lastControllingType != controllingType) {
            lastControllingType = controllingType;
            lastMethod = null;
            sharedBuffer.append("\n\n").append(controllingType.getRawName()).append("\n----------------------------\n\n");
        }
        if (method != lastMethod) {
            if (method != null) {
                sharedBuffer.append(method.getMethodPrototype().toString()).append("\n");
            }
            lastMethod = method;
        }
        sharedBuffer.append("  ").append(error).append("\n");
    }

    @Override
    public void close() {
        // Buffer is written by ZipDumperFactory.close() — nothing to do here
    }
}
