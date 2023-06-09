/*
 * Copyright 2019 The TensorFlow Authors
 * Copyright 2023 LibreMobileOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.libremobileos.yifan.face;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Trace;

import com.libremobileos.yifan.util.GpuDelegateFactory;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/** Wrapper for frozen detection models trained using the Tensorflow Object Detection API */
/* package-private */ class TFLiteObjectDetectionAPIModel extends SimilarityClassifier {

    private static final int OUTPUT_SIZE = 512;
    // private static final int OUTPUT_SIZE = 192;

    // Only return this many results.
    private static final int NUM_DETECTIONS = 10;

    // Float model
    private static final float FIRST_CHANNEL_MEAN = 131.0912f;
    private static final float SECOND_CHANNEL_MEAN = 103.8827f;
    private static final float THIRD_CHANNEL_MEAN = 91.4953f;

    private static final String SYSTEM_MODEL_DIR = "/system/etc/face";

    private boolean isModelQuantized;
    // Config values.
    private int inputSize;
    // Pre-allocated buffers.
    private final Vector<String> labels = new Vector<>();
    private int[] intValues;
    // outputLocations: array of shape [Batch-size, NUM_DETECTIONS,4]
    // contains the location of detected boxes
    private float[][][] outputLocations;
    // outputClasses: array of shape [Batch-size, NUM_DETECTIONS]
    // contains the classes of detected boxes
    private float[][] outputClasses;
    // outputScores: array of shape [Batch-size, NUM_DETECTIONS]
    // contains the scores of detected boxes
    private float[][] outputScores;
    // numDetections: array of shape [Batch-size]
    // contains the number of detected boxes
    private float[] numDetections;

    private float[][] embeddings;

    private ByteBuffer imgData;

    private Interpreter tfLite;

    private TFLiteObjectDetectionAPIModel() {}

    /** Memory-map the model file in Assets. */
    private static MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename)
            throws IOException {
        FileChannel fileChannel;
        long startOffset;
        long declaredLength;
        try {
            AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            fileChannel = inputStream.getChannel();
            startOffset = fileDescriptor.getStartOffset();
            declaredLength = fileDescriptor.getDeclaredLength();
        } catch (Exception e) {
            File f = new File(SYSTEM_MODEL_DIR, modelFilename);
            if (f.exists() && f.canRead()) {
                FileInputStream inputStream = new FileInputStream(f);
                fileChannel = inputStream.getChannel();
                startOffset = 0;
                declaredLength = f.length();
            } else {
                throw new IOException(
                        modelFilename + " not found in assets or " + SYSTEM_MODEL_DIR);
            }
        }
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Initializes a native TensorFlow session for classifying images.
     *
     * @param assetManager The asset manager to be used to load assets.
     * @param modelFilename The filepath of the model GraphDef protocol buffer.
     * @param labelFilename The filepath of label file for classes.
     * @param inputSize The size of image input
     * @param isQuantized Boolean representing model is quantized or not
     * @param hwAcceleration Enable hardware acceleration (NNAPI/GPU)
     * @param useEnhancedAcceleration if hwAcceleration is enabled, use NNAPI instead of GPU. if
     *     not, this toggles XNNPACK
     * @param numThreads How many threads to use, if running on CPU or with XNNPACK
     */
    public static SimilarityClassifier create(
            final AssetManager assetManager,
            final String modelFilename,
            final String labelFilename,
            final int inputSize,
            final boolean isQuantized,
            final boolean hwAcceleration,
            final boolean useEnhancedAcceleration,
            final int numThreads)
            throws IOException {

        final TFLiteObjectDetectionAPIModel d = new TFLiteObjectDetectionAPIModel();

        InputStream labelsInput;
        try {
            labelsInput = assetManager.open(labelFilename);
        } catch (Exception e) {
            File f = new File(SYSTEM_MODEL_DIR, labelFilename);
            if (f.exists() && f.canRead()) {
                labelsInput = new FileInputStream(f);
            } else {
                throw new IOException(
                        labelFilename + " not found in assets or " + SYSTEM_MODEL_DIR);
            }
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(labelsInput));
        String line;
        while ((line = br.readLine()) != null) {
            d.labels.add(line);
        }
        br.close();

        d.inputSize = inputSize;

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(numThreads);
        options.setUseXNNPACK(hwAcceleration || useEnhancedAcceleration);
        if (hwAcceleration) {
            if (useEnhancedAcceleration) {
                options.addDelegate(new NnApiDelegate());
            } else {
                options.addDelegate(GpuDelegateFactory.get());
            }
        }

        try {
            d.tfLite = new Interpreter(loadModelFile(assetManager, modelFilename), options);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        d.isModelQuantized = isQuantized;
        // Pre-allocate buffers.
        int numBytesPerChannel;
        if (isQuantized) {
            numBytesPerChannel = 1; // Quantized
        } else {
            numBytesPerChannel = 4; // Floating point
        }
        d.imgData = ByteBuffer.allocateDirect(d.inputSize * d.inputSize * 3 * numBytesPerChannel);
        d.imgData.order(ByteOrder.nativeOrder());
        d.intValues = new int[d.inputSize * d.inputSize];

        d.outputLocations = new float[1][NUM_DETECTIONS][4];
        d.outputClasses = new float[1][NUM_DETECTIONS];
        d.outputScores = new float[1][NUM_DETECTIONS];
        d.numDetections = new float[1];
        return d;
    }

    @Override
    public List<Recognition> recognizeImage(final Bitmap bitmap) {
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage");

        Trace.beginSection("preprocessBitmap");
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        bitmap.getPixels(
                intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        imgData.rewind();
        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < inputSize; j++) {
                int pixelValue = intValues[i * inputSize + j];
                if (isModelQuantized) {
                    // Quantized model
                    imgData.put((byte) ((pixelValue >> 16) & 0xFF));
                    imgData.put((byte) ((pixelValue >> 8) & 0xFF));
                    imgData.put((byte) (pixelValue & 0xFF));
                } else { // Float model
                    imgData.putFloat(((pixelValue >> 16) & 0xFF) - THIRD_CHANNEL_MEAN);
                    imgData.putFloat(((pixelValue >> 8) & 0xFF) - SECOND_CHANNEL_MEAN);
                    imgData.putFloat((pixelValue & 0xFF) - FIRST_CHANNEL_MEAN);
                }
            }
        }
        Trace.endSection(); // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed");

        Map<Integer, Object> outputMap = new HashMap<>();

        Object[] inputArray = {imgData};

        Trace.endSection();

        if (!isModelQuantized) {
            // Here outputMap is changed to fit the Face Mask detector
            embeddings = new float[1][OUTPUT_SIZE];
            outputMap.put(0, embeddings);
        } else {
            outputLocations = new float[1][NUM_DETECTIONS][4];
            outputClasses = new float[1][NUM_DETECTIONS];
            outputScores = new float[1][NUM_DETECTIONS];
            numDetections = new float[1];
            outputMap.put(0, outputLocations);
            outputMap.put(1, outputClasses);
            outputMap.put(2, outputScores);
            outputMap.put(3, numDetections);
        }

        // Run the inference call.
        Trace.beginSection("run");
        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);
        Trace.endSection();

        final ArrayList<Recognition> recognitions =
                new ArrayList<>(isModelQuantized ? NUM_DETECTIONS : 1);

        if (!isModelQuantized) {

            float distance = Float.MAX_VALUE;
            String id = "0";
            String label = "?";

            Recognition rec = new Recognition(id, label, distance, new RectF());

            recognitions.add(rec);

            rec.setExtra(embeddings);
        } else {
            // Show the best detections.
            // after scaling them back to the input size.
            for (int i = 0; i < NUM_DETECTIONS; ++i) {
                final RectF detection =
                        new RectF(
                                outputLocations[0][i][1] * inputSize,
                                outputLocations[0][i][0] * inputSize,
                                outputLocations[0][i][3] * inputSize,
                                outputLocations[0][i][2] * inputSize);
                // SSD Mobilenet V1 Model assumes class 0 is background class
                // in label file and class labels start from 1 to number_of_classes+1,
                // while outputClasses correspond to class index from 0 to number_of_classes
                int labelOffset = 1;

                recognitions.add(
                        new Recognition(
                                "" + i,
                                labels.get((int) outputClasses[0][i] + labelOffset),
                                outputScores[0][i],
                                detection));
            }
        }

        Trace.endSection();
        return recognitions;
    }
}
