package com.riscure.trs;

import com.riscure.trs.enums.Encoding;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;

import static com.riscure.trs.enums.TRSTag.*;

public class TraceSet implements AutoCloseable {
    private static final String ERROR_READING_FILE = "Error reading TRS file: file size (%d) != meta data (%d) + trace size (%d) * nr of traces (%d)";
    private static final String TRACE_SET_NOT_OPEN = "TraceSet has not been opened or has been closed.";
    private static final String TRACE_SET_IN_WRITE_MODE = "TraceSet is in write mode. Please open the TraceSet in read mode.";
    private static final String TRACE_INDEX_OUT_OF_BOUNDS = "Requested trace index (%d) is larger than the total number of available traces (%d).";
    private static final String TRACE_SET_IN_READ_MODE = "TraceSet is in read mode. Please open the TraceSet in write mode.";
    private static final String TRACE_LENGTH_DIFFERS = "All traces in a set need to be the same length, but current trace length (%d) differs from the previous trace(s) (%d)";
    private static final String TRACE_DATA_LENGTH_DIFFERS = "All traces in a set need to have the same data length, but current trace data length (%d) differs from the previous trace(s) (%d)";
    private static final String TRACE_SAMPLING_FREQUENCY_DIFFERS = "All traces in a set need to have the same sampling frequency, but current trace data (%.05f) differs from the previous trace(s) (%.05f)";
    private static final String UNKNOWN_SAMPLE_CODING = "Error reading TRS file: unknown sample coding '%d'";
    private static final long MAX_BUFFER_SIZE = Integer.MAX_VALUE;

    //Reading variables
    private int metaDataSize;
    private FileInputStream readStream;
    private FileChannel channel;

    private MappedByteBuffer buffer;

    private long bufferStart;       //the byte index of the file where the buffer window starts
    private long bufferSize;        //the number of bytes that are in the buffer window
    private long fileSize;          //the total number of bytes in the underlying file

    //Writing variables
    private FileOutputStream writeStream;

    private boolean firstTrace = true;

    //Shared variables
    private TRSMetaData metaData;
    private boolean open;
    private boolean writing;        //whether the trace is opened in write mode

    private TraceSet(FileInputStream stream) throws IOException, TRSFormatException {
        this.writing = false;
        this.open = true;
        this.readStream = stream;
        this.channel = stream.getChannel();

        //the file might be bigger than the buffer, in which case we partially buffer it in memory
        this.fileSize = this.channel.size();
        this.bufferStart = 0L;
        this.bufferSize = Math.min(fileSize, MAX_BUFFER_SIZE);

        mapBuffer();
        this.metaData = TRSMetaDataUtils.readTRSMetaData(buffer);
        this.metaDataSize = buffer.position();
    }

    private TraceSet(FileOutputStream stream, TRSMetaData metaData) {
        this.open = true;
        this.writing = true;
        this.metaData = metaData;
        this.writeStream = stream;
    }

    private void mapBuffer() throws IOException {
        this.buffer = this.channel.map(FileChannel.MapMode.READ_ONLY, this.bufferStart, this.bufferSize);
    }

    private void moveBufferIfNecessary(int traceIndex) throws IOException {
        long traceSize = calculateTraceSize();
        long start = metaDataSize + (long) traceIndex * traceSize;
        long end = start + traceSize;

        boolean moveRequired = start < this.bufferStart || this.bufferStart + this.bufferSize < end;
        if (moveRequired) {
            this.bufferStart = start;
            this.bufferSize = Math.min(this.fileSize - start, MAX_BUFFER_SIZE);
            this.mapBuffer();
        }
    }

    private long calculateTraceSize() {
        int sampleSize = Encoding.fromValue(metaData.getInt(SAMPLE_CODING)).getSize();
        long sampleSpace = metaData.getInt(NUMBER_OF_SAMPLES) * sampleSize;
        return sampleSpace + metaData.getInt(DATA_LENGTH) + metaData.getInt(TITLE_SPACE);
    }

    /**
     * Get a trace from the set at the specified index
     * @param index the index of the Trace to read from the file
     * @return the Trace at the requested trace index
     * @throws IOException if a read error occurs
     * @throws IllegalArgumentException if this TraceSet is not ready be read from
     */
    public Trace get(int index) throws IOException {
        if (!open) throw new IllegalArgumentException(TRACE_SET_NOT_OPEN);
        if (writing) throw new IllegalArgumentException(TRACE_SET_IN_WRITE_MODE);

        moveBufferIfNecessary(index);

        long traceSize = calculateTraceSize();
        long nrOfTraces = this.metaData.getInt(NUMBER_OF_TRACES);
        if (index >= nrOfTraces) {
            String msg = String.format(TRACE_INDEX_OUT_OF_BOUNDS, index, nrOfTraces);
            throw new IllegalArgumentException(msg);
        }

        long calculatedFileSize = metaDataSize + traceSize * nrOfTraces;
        if (fileSize != calculatedFileSize) {
            String msg = String.format(ERROR_READING_FILE, fileSize, metaDataSize, traceSize, nrOfTraces);
            throw new IllegalStateException(msg);
        }
        long absolutePosition = metaDataSize + index * traceSize;
        buffer.position((int) (absolutePosition - this.bufferStart));

        String traceTitle = this.readTraceTitle();
        if (traceTitle.trim().isEmpty()) {
            traceTitle = String.format("%s %d", metaData.getString(GLOBAL_TITLE), index);
        }
        byte[] data = readData();

        try {
            float[] samples = readSamples();
            return new Trace(traceTitle, data, samples, 1f/metaData.getFloat(SCALE_X));
        } catch (TRSFormatException ex) {
            throw new IOException(ex);
        }
    }

    /**
     * Add a trace to a writable TraceSet
     * @param trace the Trace object to add
     * @throws IOException if any write error occurs
     * @throws TRSFormatException if the formatting of the trace is invalid
     */
    public void add(Trace trace) throws IOException, TRSFormatException {
        if (!open) throw new IllegalArgumentException(TRACE_SET_NOT_OPEN);
        if (!writing) throw new IllegalArgumentException(TRACE_SET_IN_READ_MODE);
        if (firstTrace) {
            int dataLength = trace.getData() == null ? 0 : trace.getData().length;
            int titleLength = trace.getTitle() == null ? 0 : trace.getTitle().length();
            metaData.put(NUMBER_OF_SAMPLES, trace.getNumberOfSamples(), false);
            metaData.put(DATA_LENGTH, dataLength, false);
            metaData.put(TITLE_SPACE, titleLength, false);
            metaData.put(SCALE_X, 1f/trace.getSampleFrequency(), false);
            metaData.put(SAMPLE_CODING, trace.getPreferredCoding(), false);
            TRSMetaDataUtils.writeTRSMetaData(writeStream, metaData);
            firstTrace = false;
        }
        int numberOfSamples = metaData.getInt(NUMBER_OF_SAMPLES);
        int dataLength = metaData.getInt(DATA_LENGTH);
        float sampleFrequency = 1f/metaData.getFloat(SCALE_X);
        checkValid(trace, numberOfSamples, dataLength, sampleFrequency);

        writeTrace(trace);

        int numberOfTraces = metaData.getInt(NUMBER_OF_TRACES);
        metaData.put(NUMBER_OF_TRACES, numberOfTraces + 1);
    }

    private void writeTrace(Trace trace) throws TRSFormatException, IOException {
        String title = trace.getTitle() == null ? "" : trace.getTitle();
        int titleSpace = metaData.getInt(TITLE_SPACE);
        writeStream.write(Arrays.copyOf(title.getBytes(), titleSpace));
        byte[] data = trace.getData() == null ? new byte[0] : trace.getData();
        writeStream.write(data);
        Encoding encoding = Encoding.fromValue(metaData.getInt(SAMPLE_CODING));
        writeStream.write(toByteArray(trace.getSample(), encoding));
    }

    private byte[] toByteArray(float[] samples, Encoding encoding) throws TRSFormatException {
        byte[] result;
        switch (encoding) {
            case ILLEGAL:
                throw new TRSFormatException("Illegal sample encoding");
            case BYTE:
                result = new byte[samples.length];
                for (int k = 0; k < samples.length; k++) {
                    if (samples[k] != (byte)samples[k]) throw new IllegalArgumentException("Byte sample encoding too small");
                    result[k] = (byte) samples[k];
                }
                break;
            case SHORT:
                result = new byte[samples.length * 2];
                for (int k = 0; k < samples.length; k++) {
                    if (samples[k] != (short)samples[k]) throw new IllegalArgumentException("Short sample encoding too small");
                    short value = (short) samples[k];
                    result[2*k] = (byte) value;
                    result[2*k + 1] = (byte) (value >> 8);
                }
                break;
            case INT:
                result = new byte[samples.length * 4];
                for (int k = 0; k < samples.length; k++) {
                    int value = (int) samples[k];
                    result[4*k] = (byte) value;
                    result[4*k + 1] = (byte) (value >> 8);
                    result[4*k + 2] = (byte) (value >> 16);
                    result[4*k + 3] = (byte) (value >> 24);
                }
                break;
            case FLOAT:
                result = new byte[samples.length * 4];
                for (int k = 0; k < samples.length; k++) {
                    int value = Float.floatToIntBits(samples[k]);
                    result[4*k] = (byte) value;
                    result[4*k + 1] = (byte) (value >> 8);
                    result[4*k + 2] = (byte) (value >> 16);
                    result[4*k + 3] = (byte) (value >> 24);
                }
                break;
            default:
                throw new TRSFormatException("Sample encoding not supported: %s", encoding.name());
        }
        return result;
    }

    @Override
    public void close() throws IOException, TRSFormatException {
        open = false;
        if (writing) closeWriter();
        else closeReader();
    }

    private void checkValid(Trace trace, int numberOfSamples, int dataLength, float sampleFrequency) {
        if (numberOfSamples != trace.getNumberOfSamples()) {
            throw new IllegalArgumentException(String.format(TRACE_LENGTH_DIFFERS,
                    trace.getNumberOfSamples(),
                    numberOfSamples));
        }
        int traceDataLength = trace.getData() == null ? 0 : trace.getData().length;
        if (dataLength != traceDataLength) {
            throw new IllegalArgumentException(String.format(TRACE_DATA_LENGTH_DIFFERS,
                    traceDataLength,
                    dataLength));
        }
        if (sampleFrequency != trace.getSampleFrequency()) {
            throw new IllegalArgumentException(String.format(TRACE_SAMPLING_FREQUENCY_DIFFERS,
                    trace.getSampleFrequency(),
                    sampleFrequency));
        }
    }

    private void closeReader() throws IOException {
        readStream.close();
    }

    private void closeWriter() throws IOException, TRSFormatException {
        try {
            //reset writer to start of file and overwrite header
            writeStream.getChannel().position(0);
            TRSMetaDataUtils.writeTRSMetaData(writeStream, metaData);
            writeStream.flush();
        } finally {
            writeStream.close();
        }
    }

    /**
     * Get the metadata associated with this trace set
     * @return the metadata associated with this trace set
     */
    public TRSMetaData getMetaData() {
        return metaData;
    }

    protected String readTraceTitle() {
        byte[] titleArray = new byte[metaData.getInt(TITLE_SPACE)];
        buffer.get(titleArray);
        return new String(titleArray);
    }

    protected byte[] readData() {
        int inputSize = metaData.getInt(DATA_LENGTH);
        byte[] comDataArray = new byte[inputSize];
        buffer.get(comDataArray);
        return comDataArray;
    }

    protected float[] readSamples() throws TRSFormatException {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int numberOfSamples = metaData.getInt(NUMBER_OF_SAMPLES);
        float[] samples;
        switch (Encoding.fromValue(metaData.getInt(SAMPLE_CODING))) {
            case BYTE:
                byte[] byteData = new byte[numberOfSamples];
                buffer.get(byteData);
                samples = toFloatArray(byteData);
                break;
            case SHORT:
                ShortBuffer shortView = buffer.asShortBuffer();
                short[] shortData = new short[numberOfSamples];
                shortView.get(shortData);
                samples = toFloatArray(shortData);
                break;
            case FLOAT:
                FloatBuffer floatView = buffer.asFloatBuffer();
                samples = new float[numberOfSamples];
                floatView.get(samples);
                break;
            case INT:
                IntBuffer intView = buffer.asIntBuffer();
                int[] intData = new int[numberOfSamples];
                intView.get(intData);
                samples = toFloatArray(intData);
                break;
            default:
                throw new TRSFormatException(UNKNOWN_SAMPLE_CODING, metaData.getInt(SAMPLE_CODING));
        }

        return samples;
    }

    private float[] toFloatArray(byte[] numbers) {
        float[] result = new float[numbers.length];
        for (int k = 0; k < numbers.length; k++) {
            result[k] = numbers[k];
        }
        return result;
    }

    private float[] toFloatArray(int[] numbers) {
        float[] result = new float[numbers.length];
        for (int k = 0; k < numbers.length; k++) {
            result[k] = (float) numbers[k];
        }
        return result;
    }

    private float[] toFloatArray(short[] numbers) {
        float[] result = new float[numbers.length];
        for (int k = 0; k < numbers.length; k++) {
            result[k] = numbers[k];
        }
        return result;
    }

    /**
     * Factory method. This creates a new open TraceSet for reading.
     * The resulting TraceSet is a live view on the file, and loads from the file directly.
     * Remember to close the TraceSet when done.
     * @param file the path to the TRS file to open
     * @return the TraceSet representation of the file
     * @throws IOException when any read exception is encountered
     * @throws TRSFormatException when any incorrect formatting of the TRS file is encountered
     */
    public static TraceSet open(String file) throws IOException, TRSFormatException {
        FileInputStream fis = new FileInputStream(file);
        return new TraceSet(fis);
    }

    /**
     * A one-shot creator of a TRS file. The metadata not related to the trace list is assumed to be default.
     * @param file the path to the file to save
     * @param traces the list of traces to save in the file
     * @throws IOException when any write exception is encountered
     */
    public static void save(String file, List<Trace> traces) throws IOException, TRSFormatException {
        TRSMetaData trsMetaData = TRSMetaData.create();
        save(file, traces, trsMetaData);
    }

    /**
     * A one-shot creator of a TRS file. Any unfilled fields of metadata are assumed to be default.
     * @param file the path to the file to save
     * @param traces the list of traces to save in the file
     * @param metaData the metadata associated with the set to create
     * @throws IOException when any write exception is encountered
     */
    public static void save(String file, List<Trace> traces, TRSMetaData metaData) throws IOException, TRSFormatException {
        TraceSet traceSet = create(file, metaData);
        for (Trace trace : traces) {
            traceSet.add(trace);
        }
        traceSet.close();
    }

    /**
     * Create a new traceset file at the specified location. <br>
     * NOTE: The metadata is fully defined by the first added trace. <br>
     * Every next trace is expected to adhere to the following parameters: <br>
     * NUMBER_OF_SAMPLES is equal to the number of samples in the first trace <br>
     * DATA_LENGTH is equal to the binary data size of the first trace <br>
     * TITLE_SPACE is defined by the length of the first trace title (including spaces) <br>
     * SCALE_X is defined for the whole set based on the sampling frequency of the first trace <br>
     * SAMPLE_CODING is defined for the whole set based on the values of the first trace <br>
     * @param file the path to the file to be created
     * @return a writable trace set object
     * @throws IOException if the file creation failed
     */
    public static TraceSet create(String file) throws IOException {
        TRSMetaData trsMetaData = TRSMetaData.create();
        return create(file, trsMetaData);
    }

    /**
     * Create a new traceset file at the specified location. <br>
     * NOTE: The supplied metadata is leading, and is not overwritten.
     * Please make sure that the supplied values are correct <br>
     * Every next trace is expected to adhere to the following parameters: <br>
     * NUMBER_OF_SAMPLES is equal to the number of samples in the first trace <br>
     * DATA_LENGTH is equal to the binary data size of the first trace <br>
     * TITLE_SPACE is defined by the length of the first trace title (including spaces) <br>
     * SCALE_X is defined for the whole set based on the sampling frequency of the first trace <br>
     * SAMPLE_CODING is defined for the whole set based on the values of the first trace <br>
     * @param file the path to the file to be created
     * @return a writable trace set object
     * @throws IOException if the file creation failed
     */
    public static TraceSet create(String file, TRSMetaData metaData) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);

        return new TraceSet(fos, metaData);
    }
}
