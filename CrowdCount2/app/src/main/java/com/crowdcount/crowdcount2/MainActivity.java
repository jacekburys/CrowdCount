package com.crowdcount.crowdcount2;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

import com.flir.flironesdk.*;


import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;


public class MainActivity extends Activity  implements Device.Delegate, FrameProcessor.Delegate {

    private Device flirDevice;
    private FrameProcessor frameProcessor;

    private boolean processing = false;

    synchronized private void setProcessing(boolean processing){
        this.processing = processing;
    }

    synchronized private boolean getProcessing() {
        return processing;
    }

    static { System.loadLibrary("opencv_java3"); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        frameProcessor = new FrameProcessor(this, this,
                EnumSet.of(RenderedImage.ImageType.BlendedMSXRGBA8888Image));

    }

    @Override
    protected void onResume() {
        super.onResume();
        Device.startDiscovery(this, this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Device.stopDiscovery();
    }



    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // DEVICE DELEGATE

    @Override
    public void onTuningStateChanged(Device.TuningState tuningState) {

    }

    @Override
    public void onAutomaticTuningChanged(boolean b) {

    }

    @Override
    public void onDeviceConnected(Device device) {
        flirDevice = device;
        device.startFrameStream(new Device.StreamDelegate() {
            @Override
            public void onFrameReceived(Frame frame) {
                if(getProcessing()) return;
                setProcessing(true);
                frameProcessor.processFrame(frame);
            }
        });
    }

    @Override
    public void onDeviceDisconnected(Device device) {

    }

    // FRAME PROCESSOR

    @Override
    public void onFrameProcessed(RenderedImage renderedImage) {

        // frame : 480 x 640

        int width = 360;
        int height = 480;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Mat orig = new Mat(renderedImage.height(), renderedImage.width(), CvType.CV_8UC4);
        orig.put(0, 0, renderedImage.pixelData());

        Mat mat = new Mat(height, width, CvType.CV_8UC4);
        Imgproc.resize(orig, mat, new Size(width, height));

        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);

        Imgproc.GaussianBlur(mat, mat, new Size(5, 5), 1);
        Imgproc.threshold(mat, mat, 0, 255, Imgproc.THRESH_OTSU);
        Imgproc.medianBlur(mat, mat, 5);

        Mat mat_8u = new Mat(height, width, CvType.CV_8U);
        mat.convertTo(mat_8u, CvType.CV_8U);

        //erode
        Imgproc.dilate(mat_8u, mat_8u, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5)));

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();

        Imgproc.findContours(mat_8u, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Collections.sort(contours, new ContourComparator());

        int biggestSize;
        int count;
        if(contours != null && contours.size() > 0){
            biggestSize = contours.get(0).toList().size();
            count = 0;
            for(MatOfPoint pt : contours){
                if(pt.toList().size() < biggestSize / 3.0){
                    break;
                }
                count++;
            }
        }else{
            biggestSize = 0;
            count = 0;
        }

        //convert back to color
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2BGR);

        Scalar[] arr = {new Scalar(255, 0, 0), new Scalar(0, 255, 0), new Scalar(0, 0, 255)};

        for(int i = 0; i<count; i++){
            Imgproc.drawContours(mat, contours, i, arr[i%3], 3);
        }

        Utils.matToBitmap(mat, bitmap);

        final Bitmap imageBitmap = bitmap;
        final int finalCount = count;

        final TextView textView = (TextView)findViewById(R.id.textView);
        final ImageView imageView = (ImageView)findViewById(R.id.imageView);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.setText(finalCount + " objects");
                imageView.setImageBitmap(imageBitmap);

            }
        });

        setProcessing(false);
    }


    private class ContourComparator implements Comparator<MatOfPoint> {
        @Override
        public int compare(MatOfPoint lhs, MatOfPoint rhs) {
            return rhs.toList().size()-lhs.toList().size();
        }
    }
}
