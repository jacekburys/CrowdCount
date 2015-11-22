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
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


public class MainActivity extends Activity  implements Device.Delegate, FrameProcessor.Delegate {

    private Device flirDevice;
    private FrameProcessor frameProcessor;

    private boolean processing = false;

    private int MIN_TEMP = 192;
    private double DIST_THRESH = 150;

    private int TOTAL = 0;

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

    private List<Point> prevPoints = new ArrayList<>();
    private List<Point> currPoints = new ArrayList<>();

    @Override
    public void onFrameProcessed(final RenderedImage renderedImage) {

        // frame : 480 x 640

        prevPoints = currPoints;
        currPoints = new ArrayList<>();

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


        //dilate
        //Imgproc.dilate(mat_8u, mat_8u, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5)));

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();

        Imgproc.findContours(mat_8u, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Collections.sort(contours, new ContourComparator());

        int biggestSize;
        int count;
        int COUNT_LIMIT = 5;
        if(contours != null && contours.size() > 0){
            biggestSize = contours.get(0).toList().size();
            count = 0;
            for(MatOfPoint pt : contours){
                if(pt.toList().size() < biggestSize / 2.0){
                    break;
                }
                count++;
                if(count >= COUNT_LIMIT) break;
            }
        }else{
            biggestSize = 0;
            count = 0;
        }

        //convert back to color
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2BGR);

        Scalar[] arr = {new Scalar(255, 0, 0), new Scalar(0, 255, 0), new Scalar(0, 0, 255)};

        MatOfPoint2f approxCurve = new MatOfPoint2f();

        for(int i = 0; i<count; i++){
            Imgproc.drawContours(mat, contours, i, arr[i%3], 3);

            //Convert contours(i) from MatOfPoint to MatOfPoint2f
            MatOfPoint2f contour2f = new MatOfPoint2f( contours.get(i).toArray() );
            //Processing on mMOP2f1 which is in type MatOfPoint2f
            double approxDistance = Imgproc.arcLength(contour2f, true)*0.02;
            Imgproc.approxPolyDP(contour2f, approxCurve, approxDistance, true);

            //Convert back to MatOfPoint
            MatOfPoint points = new MatOfPoint( approxCurve.toArray() );

            // Get bounding rect of contour
            Rect rect = Imgproc.boundingRect(points);

            // draw enclosing rectangle (all same color, but you could use variable i to make them unique)

            Imgproc.rectangle(mat, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height),
                    arr[i % 3], Imgproc.LINE_4);
            Point center = new Point(rect.x + rect.width/2, rect.y + rect.height/2);
            Imgproc.circle(mat, center, 5, new Scalar(255, 255, 0), -1);

            currPoints.add(center);
        }

        // match points

        HashMap<Point, Integer> labelMap = new HashMap<>();
        int LABEL = 0;

        List<PointsDist> dists = new ArrayList<>();

        for(int i=0; i<prevPoints.size(); i++) {
            for(int j=0; j<currPoints.size(); j++) {
                dists.add(new PointsDist(prevPoints.get(i), currPoints.get(j)));
            }
        }

        Collections.sort(dists, new DistComparator());

        HashSet<Point> usedPrev = new HashSet<>();
        HashSet<Point> usedCurr = new HashSet<>();

        for(PointsDist d : dists) {
            if(d.dist > DIST_THRESH) break;
            if(usedPrev.contains(d.p1) || usedCurr.contains(d.p2)) continue;

            Imgproc.line(mat, d.p1, d.p2, new Scalar(255, 0, 0), 3);

            usedPrev.add(d.p1);
            usedCurr.add(d.p2);

            if(labelMap.get(d.p1) != null){
                labelMap.put(d.p2, labelMap.get(d.p1));
            }else{
                labelMap.put(d.p2, LABEL);
                LABEL++;
            }

        }

        for(Point p : currPoints) {
            if(!labelMap.containsKey(p)) {
                labelMap.put(p, LABEL);
                LABEL++;
            }
            Imgproc.putText(mat, "" + labelMap.get(p), p, 0, 1, new Scalar(255, 0, 255));
        }

        //


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


/*
        try{
            Thread.sleep(100);
        }catch (Exception e){

        }
*/

        setProcessing(false);
    }

    private class PointsDist {
        public Point p1, p2;
        public double dist;
        public PointsDist(Point p1, Point p2) {
            this.p1 = p1;
            this.p2 = p2;
            this.dist = Math.pow(Math.abs(p1.x - p2.x), 2) + Math.pow(Math.abs(p1.y - p2.y), 2);
        }
    }


    private class DistComparator implements Comparator<PointsDist> {
        @Override
        public int compare(PointsDist lhs, PointsDist rhs) {
            return (int)(lhs.dist - rhs.dist);
        }
    }

    private class ContourComparator implements Comparator<MatOfPoint> {
        @Override
        public int compare(MatOfPoint lhs, MatOfPoint rhs) {
            return rhs.toList().size()-lhs.toList().size();
        }
    }
}
