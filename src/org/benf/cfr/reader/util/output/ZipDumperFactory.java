package org.benf.cfr.reader.util.output;

import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.state.TypeUsageInformation;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.getopt.OptionsImpl;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipDumperFactory implements DumperFactory {
    private final ZipOutputStream zos;
    private final Options options;
    private final ProgressDumper progressDumper;
    private final String prefix;
    // Shared across all prefix-copies of this factory; written to summary.txt on close()
    private final StringBuilder summaryBuffer;

    public ZipDumperFactory(String zipPath, Options options) throws IOException {
        this.options = options;
        File zipFile = new File(zipPath);
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        this.zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
        if (!options.getOption(OptionsImpl.SILENT)) {
            this.progressDumper = new ProgressDumperStdErr();
        } else {
            this.progressDumper = ProgressDumperNop.INSTANCE;
        }
        this.prefix = "";
        this.summaryBuffer = new StringBuilder();
    }

    private ZipDumperFactory(ZipDumperFactory other, String prefix) {
        this.zos = other.zos;
        this.options = other.options;
        this.progressDumper = other.progressDumper;
        this.prefix = prefix;
        this.summaryBuffer = other.summaryBuffer;
    }

    @Override
    public Dumper getNewTopLevelDumper(JavaTypeInstance classType, SummaryDumper summaryDumper,
                                       TypeUsageInformation typeUsageInformation,
                                       IllegalIdentifierDump illegalIdentifierDump) {
        return new ZipFileDumper(zos, prefix, classType, summaryDumper, typeUsageInformation, options, illegalIdentifierDump);
    }

    @Override
    public Dumper wrapLineNoDumper(Dumper dumper) {
        // Line number table output not supported in ZIP mode
        return dumper;
    }

    @Override
    public ProgressDumper getProgressDumper() {
        return progressDumper;
    }

    @Override
    public SummaryDumper getSummaryDumper() {
        return new ZipSummaryDumper(summaryBuffer);
    }

    @Override
    public ExceptionDumper getExceptionDumper() {
        return new StdErrExceptionDumper();
    }

    @Override
    public DumperFactory getFactoryWithPrefix(String prefix, int version) {
        return new ZipDumperFactory(this, this.prefix + prefix);
    }

    public void close() {
        try {
            if (summaryBuffer.length() > 0) {
                zos.putNextEntry(new ZipEntry("summary.txt"));
                zos.write(summaryBuffer.toString().getBytes("UTF-8"));
                zos.closeEntry();
            }
            zos.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
