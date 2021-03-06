/*
 * Copyright (C) 2017 Laboratory of Experimental Biophysics
 * Ecole Polytechnique Federale de Lausanne
 * 
 * Author: Marcel Stefko
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ch.epfl.leb.alica.workers;

import ch.epfl.leb.alica.AlicaLogger;
import ch.epfl.leb.alica.interfaces.Analyzer;
import ch.epfl.leb.alica.ImagingMode;
import com.google.common.eventbus.Subscribe;
import ij.gui.Roi;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.json.JSONObject;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.data.DataProviderHasNewImageEvent;
import org.micromanager.events.AcquisitionEndedEvent;
import org.micromanager.events.AcquisitionStartedEvent;
import org.micromanager.events.LiveModeEvent;

/**
 * This thread continuously queries either the MMCore, or the processing pipeline
 * of the live mode for new images, and calls the analyzer's processImage() method
 * on them as fast as it can. Always the latest image is taken for analysis, so
 * it is possible for images to be skipped. It also gathers some statistics for
 * display by the GUI.
 * @author Marcel Stefko
 */
public class AnalysisWorker extends Thread {
    private boolean stop_flag = false;
    
    private final Coordinator coordinator;
    private final Studio studio;
    private final Analyzer analyzer;
    private final ImagingMode imaging_mode;
    
    // recieves signals from the live view Datastore, and passes the latest
    // Coords to last_live_image_coords
    private final NewImageWatcher new_image_watcher;
    private Coords last_live_image_coords = null;
    // for comparison with newly acquired images, to see if the image has
    // changed
    private JSONObject last_core_image_tag = null;
    
    // for GUI output
    private long last_analysis_time_ms = 0;
    private int last_fps_count = 0;
    
    // for logging
    private int image_counter = 0;
    
    /**
     * Initialize the worker.
     * @param coordinator parent Coordinator
     * @param studio for logging and image queries
     * @param analyzer this Analyzer's processImage() method is called on gathered images
     * @param imaging_mode
     */
    public AnalysisWorker(Coordinator coordinator, Studio studio, Analyzer analyzer, ImagingMode imaging_mode) {
        this.setName("Analysis Worker");
        
        this.coordinator = coordinator;
        this.studio = studio;
        this.analyzer = analyzer;
        this.imaging_mode = imaging_mode;
        
        this.new_image_watcher = new NewImageWatcher(this.analyzer, this);
    }
    
    /**
     * Called by the NewImageWatcher to update last coords
     * @param coords new Coords
     */
    void setLastImageCoords(Coords coords) {
        this.last_live_image_coords = coords;
    }
    
    /**
     * Called by the MMCore to signalize there is a new live mode. 
     * If the imaging mode is LIVE, the NewImageWatcher will be informed.
     * @param evt new live mode event
     */
    @Subscribe
    public void liveModeStarted(LiveModeEvent evt) {
        if (evt.getIsOn() && imaging_mode.equals(ImagingMode.LIVE)) {
            studio.logs().logDebugMessage("Live mode start detected. Alica " +
                                          "analyzer thread will now begin " +
                                          "analyzing images from pipeline.");
            this.new_image_watcher.setLatestDatastore(this.studio.live().getDisplay().getDatastore());
            this.image_counter = 0;
            AlicaLogger.getInstance().clear();
        }
    }
    
    /**
     * If the imaging mode is NEXT_ACQUISITION, the NewImageWatcher will be informed.
     * @param evt new acquisition started event
     */
    @Subscribe
    public void acquisitionStarted(AcquisitionStartedEvent evt) {
        if (imaging_mode.equals(ImagingMode.NEXT_ACQUISITION)) {
            studio.logs().logDebugMessage("Acquisition start detected. Alica analyzer thread will now begin analyzing images from pipeline.");
            this.new_image_watcher.setLatestDatastore(evt.getDatastore());
            this.image_counter = 0;
            AlicaLogger.getInstance().clear();
        }
        AlicaLogger.getInstance().addToLog(image_counter, "Acquisition_in_progress", 1.0);
    }
    
    /**
     * If the imaging mode is NEXT_ACQUISITION, the coordinator will 
     * asked to stop.
     * @param evt acquisition stopped
     */
    @Subscribe
    public void acquisitionEnded(AcquisitionEndedEvent evt) {
        if (imaging_mode.equals(ImagingMode.NEXT_ACQUISITION)) {
            studio.logs().logDebugMessage("Acquisition end detected. Alica worker threads will now terminate.");
            this.new_image_watcher.setLatestDatastore(null);
            this.coordinator.requestStop();
        }
        AlicaLogger.getInstance().addToLog(image_counter, "Acquisition_in_progress", 0.0);
    }
    
    /**
     * Set the ROI for Analyzer
     * @param roi ROI to be set
     */
    public void setROI(Roi roi) {
        synchronized(analyzer) {
            analyzer.setROI(roi);
        }
    }        
    
    
    
    @Override
    public void run() {
        // FPS counters
        long fps_time = coordinator.getTimeMillis();
        int fps_count = 0;
        // loop while asked to stop
        while (!this.stop_flag) {
            // either draw from core or live mode datastore
            try {
                if (imaging_mode.equals(ImagingMode.GRAB_FROM_CORE))
                    getNewImageFromCoreAndAnalyze();
                else
                    getNewImageFromWatcherAndAnalyze();
            } catch (InterruptedException ex) {
                studio.logs().logMessage("Analysis Worker was interrupted.");
                break;
            }
            // increment fps counter after each image
            fps_count++;
            image_counter++;
            // log the intermittent output
            AlicaLogger.getInstance().addIntermittentOutput(image_counter, this.queryAnalyzerForIntermittentOutput());
            
            // if a second has passed, store value and reset FPS counters
            if ((coordinator.getTimeMillis() - fps_time) > 1000) {
                last_fps_count = fps_count;
                fps_count = 0;
                fps_time = coordinator.getTimeMillis();
            }
        }
        // after finishing analysis, dispose of analyzer resources
        analyzer.dispose();
    }
    
    /**
     * Grabs new images from the Datastore associated with the NewImageWatcher, analyzes
     * it.
     * @throws java.lang.InterruptedException
     */
    public void getNewImageFromWatcherAndAnalyze() throws InterruptedException {
        Coords current_coords;
        // wait for image acquisition by NewImageWatcher
        synchronized(analyzer) {
            if (this.last_live_image_coords==null) {
                analyzer.wait();
            }
            current_coords = this.last_live_image_coords;
        }
        long image_acquisition_time = coordinator.getTimeMillis();
        try {
            Image img = this.new_image_watcher.getLatestDatastore().getImage(current_coords);
            analyzer.processImage(img.getRawPixels(), img.getWidth(), img.getHeight(), studio.core().getPixelSizeUm(), image_acquisition_time);
        } catch (Exception ex) {
            studio.logs().logError(ex, "Error in image retrieval from datastore or processing by analyzer.");
        }
        // log coords of the image, offset by 1 because counter was not yet incremented
        AlicaLogger.getInstance().addToLog(image_counter+1, "coords_time", Integer.toString(current_coords.getTime()));
        // clear last image coords pointer
        this.last_live_image_coords = null;

        last_analysis_time_ms = coordinator.getTimeMillis() - image_acquisition_time;
    }
    
    /**
     * Acquire the new image directly from MMCore and send for analysis.
     * @throws java.lang.InterruptedException if waiting is interrupted
     */
    public void getNewImageFromCoreAndAnalyze() throws InterruptedException {
        long image_acquisition_time = coordinator.getTimeMillis();
        TaggedImage new_tagged_image = null;
        Object new_image = null;
        // query core for new image, if failed, just log it and enter loop
        try {
            new_tagged_image = studio.core().getLastTaggedImage();
            new_image = new_tagged_image.pix;
        } catch (Exception ex) {
            studio.logs().logDebugMessage("Failure by AnalysisWorker to recieve image from MMCore.");
        }
        // if no image was detected, or is the same as last analyzed, wait a given period and query again
        while (new_image==null || areTwoImagesEqual(new_tagged_image.tags, this.last_core_image_tag)) {
            sleep(2); // wait 2ms
            // try to get new image again
            try {
                // also update acq time value
                image_acquisition_time = coordinator.getTimeMillis();
                new_tagged_image = studio.core().getLastTaggedImage();
                new_image = new_tagged_image.pix;
            } catch (Exception ex) {
                studio.logs().logDebugMessage("Failure by AnalysisWorker to recieve image from MMCore.");
            }
        }
        // so we have a new image, now we process it and store the tag for later comparison
        analyzer.processImage(new_image, (int) studio.core().getImageWidth(), (int) studio.core().getImageHeight(), studio.core().getPixelSizeUm(), image_acquisition_time);
        this.last_core_image_tag = new_tagged_image.tags;
        this.last_analysis_time_ms = coordinator.getTimeMillis() - image_acquisition_time;
        try {
            // log the image number
            AlicaLogger.getInstance().addToLog(image_counter+1, "tag_frame_index", new_tagged_image.tags.getInt("ImageNumber"));
        } catch (JSONException ex) {
            studio.logs().logError(ex, "Failed to extract tagged image data.");
        }
    }
    
    /**
     * Compare the ImageNumber parameter of the JSONObjects
     * @param tag1 first image tag
     * @param tag2 second image tag
     * @return true of ImageNumber is identical, false if not or it does not exist
     */
    private boolean areTwoImagesEqual(JSONObject tag1, JSONObject tag2) {
        // if any of these images is null, they are marked as not equal
        if ((tag1==null) || (tag2==null)) {
            return false;
        }
        try {
            if (tag1.getInt("ImageNumber") == tag2.getInt("ImageNumber"))
                return true;
            else
                return false;
        } catch (JSONException ex) {
            studio.logs().logDebugMessage("Failed image from core comparison:\n"+ex.getMessage());
            return false;
        }
    }
    
    /**
     * Used for GUI rendering.
     * @return intermittent output of the analyzer
     */
    public double queryAnalyzerForIntermittentOutput() {
        synchronized(this.analyzer) {
            double out = this.analyzer.getIntermittentOutput();
            return out;
        }
    }
    
    /**
     * Analyzer's internal state might change, and the output is passed on
     * to the controller.
     * @return batched output of analyzer
     */
    public double queryAnalyzerForBatchOutput() {
        synchronized(this.analyzer) {
            double out = this.analyzer.getBatchOutput();
            AlicaLogger.getInstance().addBatchedOutput(image_counter, out);
            return out;
        }
    }
    
    /**
     * 
     * @return duration of last analysis in milliseconds
     */
    public long getLastAnalysisTime() {
        return last_analysis_time_ms;
    }
    
    /**
     *
     * @return number of analyzed frames in the last second
     */
    public int getCurrentFPS() {
        return last_fps_count;
    }
    
    /**
     * 
     * @return number of analyzed frames since last counter reset,
     *  which could be either caused by live mode start, or acquisition start.
     */
    public int getCurrentImageCount() {
        return this.image_counter;
    }
    
    /**
     * Returns the current description of the analyzer's output.
     * 
     * @return A string describing the analyzer's current output.
     */
    public String getAnalyzerShortDescription() {
        return this.analyzer.getShortReturnDescription();
    }
    
    /**
     * Stops the analyzer after finalizing the current analysis.
     */
    public void requestStop() {
        this.stop_flag = true;
    }
}

/**
 * The watcher is subscribed to a Datastore by the AnalysisWorker, and then it
 * informs the AnalysisWorker of any new images in the Datastore.
 * @author Marcel Stefko
 */
class NewImageWatcher {
    private final Object object_to_lock;
    private final AnalysisWorker thread_to_notify;
    private Datastore latest_datastore;
    
    public NewImageWatcher(Object object_to_lock, AnalysisWorker thread_to_notify) {
        this.object_to_lock = object_to_lock;
        this.thread_to_notify = thread_to_notify;
        this.latest_datastore = null;
    }
    
    public Datastore getLatestDatastore() {
        if (latest_datastore==null) {
            throw new NullPointerException("No datastore associated with watcher!");
        }
        return latest_datastore;
    }
    
    /**
     * Sets the latest datastore, and registers for its events.
     * @param store 
     */
    public void setLatestDatastore(Datastore store) {
        // try unregistering from previous datastore
        if (latest_datastore != null) {
            try {
                latest_datastore.unregisterForEvents(this);
            } catch (Exception ex) {
                AlicaLogger.getInstance().logError(ex, "Failure in unsubscribing NewImageWatcher from events.");
            }
        }
        store.registerForEvents(this);
        this.latest_datastore = store;
    }
    
    /**
     * Notify the thread that new image is available and send it the coords.
     * @param evt event containing coords
     */
    @Subscribe
    public void newImageAcquired(DataProviderHasNewImageEvent evt) {
        synchronized(object_to_lock) {
            // notify thread that it can wake up
            object_to_lock.notify();
            // store the last image coords
            thread_to_notify.setLastImageCoords(evt.getCoords());
        }
    }
}

