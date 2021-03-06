/*
 *  /**
 *  * Copyright (C) 2017  Grbl Controller Contributors
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation; either version 2 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  * with this program; if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *  * <http://www.gnu.org/licenses/>
 *
 */

package in.co.gorest.grblcontroller.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.CustomEvent;
import com.joanzapata.iconify.widget.IconButton;
import com.joanzapata.iconify.widget.IconTextView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import droidninja.filepicker.FilePickerBuilder;
import droidninja.filepicker.FilePickerConst;
import in.co.gorest.grblcontroller.GrblConttroller;
import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentFileSenderTabBinding;
import in.co.gorest.grblcontroller.events.BluetoothDisconnectEvent;
import in.co.gorest.grblcontroller.events.GrblErrorEvent;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.helpers.EnhancedSharedPreferences;
import in.co.gorest.grblcontroller.listners.FileSenderListner;
import in.co.gorest.grblcontroller.listners.MachineStatusListner;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.model.Overrides;
import in.co.gorest.grblcontroller.service.FileStreamerIntentService;
import in.co.gorest.grblcontroller.util.GcodePreprocessorUtils;
import in.co.gorest.grblcontroller.util.GrblUtils;

public class FileSenderTabFragment extends BaseFragment implements View.OnClickListener, View.OnLongClickListener{

    private static final String TAG = FileSenderTabFragment.class.getSimpleName();
    private MachineStatusListner machineStatus;
    private FileSenderListner fileSender;
    private EnhancedSharedPreferences sharedPref;

    public FileSenderTabFragment() {}

    public static FileSenderTabFragment newInstance() {
        return new FileSenderTabFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        machineStatus = MachineStatusListner.getInstance();
        fileSender = FileSenderListner.getInstance();
        sharedPref = EnhancedSharedPreferences.getInstance(getActivity().getApplicationContext(), getString(R.string.shared_preference_key));
    }

    @Override
    public void onStart(){
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop(){
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        FragmentFileSenderTabBinding binding = DataBindingUtil.inflate(inflater, R.layout.fragment_file_sender_tab, container, false);
        binding.setMachineStatus(machineStatus);
        binding.setFileSender(fileSender);
        View view = binding.getRoot();

        IconTextView selectGcodeFile = view.findViewById(R.id.select_gcode_file);
        selectGcodeFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragmentInteractionListener.vibrateShort();
                if(hasExternalStorageReadPermission()){
                    getFilePicker();
                }else{
                    askExternalReadPermission();
                }
            }
        });

        final IconButton enableChecking = view.findViewById(R.id.enable_checking);
        enableChecking.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragmentInteractionListener.vibrateShort();
                if(machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE) || machineStatus.getState().equals(Constants.MACHINE_STATUS_CHECK)){
                    stopFileStreaming();
                    fragmentInteractionListener.onGcodeCommandReceived(GrblUtils.GRBL_TOGGLE_CHECK_MODE_COMMAND);
                }
            }
        });

        final IconButton startStreaming = view.findViewById(R.id.start_streaming);
        startStreaming.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragmentInteractionListener.vibrateShort();
                if(fileSender.getGcodeFile() == null){
                    EventBus.getDefault().post(new UiToastEvent(getString(R.string.no_gcode_file_selected)));
                    return;
                }

                if(fileSender.getStatus().equals(FileSenderListner.STATUS_READING)){
                    EventBus.getDefault().post(new UiToastEvent(getString(R.string.file_reading_in_progress)));
                    return;
                }

                startFileStreaming();
            }
        });

        final IconButton stopStreaming = view.findViewById(R.id.stop_streaming);
        stopStreaming.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragmentInteractionListener.vibrateLong();
                stopFileStreaming();
            }
        });

        for(int resourseId: new Integer[]{R.id.feed_override_fine_minus, R.id.feed_override_fine_plus, R.id.feed_override_coarse_minus, R.id.feed_overrirde_coarse_pluse,
            R.id.spindle_override_fine_minus, R.id.spindle_override_fine_plus, R.id.spindle_override_coarse_minus, R.id.spindle_override_coarse_plus,
            R.id.rapid_overrides_reset, R.id.rapid_override_medium, R.id.rapid_overrirde_low,
            R.id.toggle_spindle, R.id.toggle_flood_coolant, R.id.toggle_mist_coolant }){
            IconButton iconButton = view.findViewById(resourseId);
            iconButton.setOnClickListener(this);
            iconButton.setOnLongClickListener(this);
        }

        return view;
    }

    private void startFileStreaming(){

        if(machineStatus.getState().equals(Constants.MACHINE_STATUS_RUN) && FileStreamerIntentService.getIsServiceRunning()){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_PAUSE_COMMAND);
            return;
        }

        if(machineStatus.getState().equals(Constants.MACHINE_STATUS_HOLD)){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESUME_COMMAND);
            return;
        }

        if(!FileStreamerIntentService.getIsServiceRunning() && (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE) || machineStatus.getState().equals(Constants.MACHINE_STATUS_CHECK))){

            if(machineStatus.getState().equals(Constants.MACHINE_STATUS_CHECK)){

                FileStreamerIntentService.setShouldContinue(true);
                Intent intent = new Intent(getActivity().getApplicationContext(), FileStreamerIntentService.class);
                intent.putExtra(FileStreamerIntentService.CHECK_MODE_ENABLED, machineStatus.getState().equals(Constants.MACHINE_STATUS_CHECK));

                String defaultConnection = sharedPref.getString(getString(R.string.default_serial_connection_type), Constants.SERIAL_CONNECTION_TYPE_BLUETOOTH);
                intent.putExtra(FileStreamerIntentService.SERIAL_CONNECTION_TYPE, defaultConnection);

                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1){
                    getActivity().getApplicationContext().startForegroundService(intent);
                }else{
                    getActivity().startService(intent);
                }
            }else{

                boolean checkMachinePosition = sharedPref.getBoolean(getString(R.string.check_machine_position_before_job), false);
                if(checkMachinePosition && !machineStatus.getWorkPosition().atZero()){
                    EventBus.getDefault().post(new UiToastEvent("Machine is not at zero position"));
                    return;
                }

                new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.starting_gcode_streaming))
                        .setMessage(getString(R.string.check_every_thing))
                        .setPositiveButton(getString(R.string.continue_streaming), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                FileStreamerIntentService.setShouldContinue(true);
                                Intent intent = new Intent(getActivity().getApplicationContext(), FileStreamerIntentService.class);
                                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1){
                                    getActivity().getApplicationContext().startForegroundService(intent);
                                }else{
                                    getActivity().startService(intent);
                                }

                                Answers.getInstance().logCustom(new CustomEvent("File Streaming")
                                        .putCustomAttribute("lines", fileSender.getRowsInFile().toString())
                                        .putCustomAttribute("size", (fileSender.getGcodeFile().length()/1024)));

                            }
                        })
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show();
            }

        }
    }

    private void stopFileStreaming(){
        if(FileStreamerIntentService.getIsServiceRunning()){
            FileStreamerIntentService.setShouldContinue(false);
        }

        Intent intent = new Intent(getActivity().getApplicationContext(), FileStreamerIntentService.class);
        getActivity().stopService(intent);

        if(machineStatus.getState().equals(Constants.MACHINE_STATUS_HOLD)){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESET_COMMAND);
        }

        String stopButtonBehaviour = sharedPref.getString(getString(R.string.streaming_stop_button_behaviour), Constants.JUST_STOP_STREAMING);
        if(machineStatus.getState().equals(Constants.MACHINE_STATUS_RUN) && stopButtonBehaviour.equals(Constants.STOP_STREAMING_AND_RESET)){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESET_COMMAND);
        }

        if(fileSender.getStatus().equals(FileSenderListner.STATUS_STREAMING)){
            fileSender.setStatus(FileSenderListner.STATUS_IDLE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == FilePickerConst.REQUEST_CODE_DOC && resultCode == Activity.RESULT_OK && data != null){
            ArrayList<String> pickedFiles = data.getStringArrayListExtra(FilePickerConst.KEY_SELECTED_DOCS);
            if(pickedFiles.size() > 0){
                fileSender.setGcodeFile(new File(pickedFiles.get(0)));
                if(fileSender.getGcodeFile().exists()){
                    new ReadFileAsyncTask().execute(fileSender.getGcodeFile());
                }else{
                    MediaScannerConnection.scanFile(getActivity().getApplicationContext(), new String[] { fileSender.getGcodeFile().getAbsolutePath() }, null,
                            new MediaScannerConnection.OnScanCompletedListener() {
                                public void onScanCompleted(String path, Uri uri) {}
                            }
                    );

                    EventBus.getDefault().post(new UiToastEvent(getString(R.string.file_not_found)));
                }
            }
        }

    }

    @Override
    public boolean onLongClick(View view){
        fragmentInteractionListener.vibrateShort();
        int id = view.getId();

        switch(id){

            case R.id.feed_override_coarse_minus:
            case R.id.feed_overrirde_coarse_pluse:
            case R.id.feed_override_fine_minus:
            case R.id.feed_override_fine_plus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_RESET);
                return true;

            case R.id.spindle_override_coarse_minus:
            case R.id.spindle_override_coarse_plus:
            case R.id.spindle_override_fine_minus:
            case R.id.spindle_override_fine_plus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_RESET);
                return true;
        }

        return false;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        fragmentInteractionListener.vibrateShort();

        switch(id) {
            case R.id.feed_override_fine_minus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_FINE_MINUS);
                break;

            case R.id.feed_override_fine_plus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_FINE_PLUS);
                break;

            case R.id.feed_override_coarse_minus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_COARSE_MINUS);
                break;

            case R.id.feed_overrirde_coarse_pluse:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_COARSE_PLUS);
                break;

            case R.id.spindle_override_fine_minus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_FINE_MINUS);
                break;

            case R.id.spindle_override_fine_plus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_FINE_PLUS);
                break;

            case R.id.spindle_override_coarse_minus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_COARSE_MINUS);
                break;

            case R.id.spindle_override_coarse_plus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_COARSE_PLUS);
                break;

            case R.id.rapid_overrirde_low:
                sendRealTimeCommand(Overrides.CMD_RAPID_OVR_LOW);
                break;

            case R.id.rapid_override_medium:
                sendRealTimeCommand(Overrides.CMD_RAPID_OVR_MEDIUM);
                break;

            case R.id.rapid_overrides_reset:
                sendRealTimeCommand(Overrides.CMD_RAPID_OVR_RESET);
                break;

            case R.id.toggle_spindle:
                sendRealTimeCommand(Overrides.CMD_TOGGLE_SPINDLE);
                break;

            case R.id.toggle_flood_coolant:
                sendRealTimeCommand(Overrides.CMD_TOGGLE_FLOOD_COOLANT);
                break;

            case R.id.toggle_mist_coolant:
                if(machineStatus.getCompileTimeOptions().mistCoolant){
                    sendRealTimeCommand(Overrides.CMD_TOGGLE_MIST_COOLANT);
                }else{
                    EventBus.getDefault().post(new UiToastEvent(getString(R.string.mist_disabled)));
                }
                break;
        }

    }

    private void sendRealTimeCommand(Overrides overrides){
        Byte command = GrblUtils.getOverrideForEnum(overrides);
        if(command != null){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(command);
        }
    }

    private static class ReadFileAsyncTask extends AsyncTask<File, Integer, Integer> {

        protected void onPreExecute(){
            FileSenderListner.getInstance().setStatus(FileSenderListner.STATUS_READING);
            this.initFileSenderListner();
        }

        protected Integer doInBackground(File... file){
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            Integer lines = 0;
            try{
                BufferedReader reader = new BufferedReader(new FileReader(file[0]));
                String sCurrentLine;
                String comment;
                while((sCurrentLine = reader.readLine()) != null){

                    comment = GcodePreprocessorUtils.parseComment(sCurrentLine);
                    if(comment.length() > 0){
                        sCurrentLine = GcodePreprocessorUtils.removeComment(sCurrentLine);
                    }
                    sCurrentLine = GcodePreprocessorUtils.removeWhiteSpace(sCurrentLine);
                    if(sCurrentLine.length() > 0){
                        lines++;
                        if(sCurrentLine.length() >= 79){
                            EventBus.getDefault().post(new UiToastEvent(GrblConttroller.getContext().getString(R.string.gcode_command_over_length_warning) + String.valueOf(lines)));
                            initFileSenderListner();
                            FileSenderListner.getInstance().setStatus(FileSenderListner.STATUS_IDLE);
                            cancel(true);
                        }
                    }
                    if(lines%2500 == 0) publishProgress(lines);
                }
                reader.close();
            }catch (IOException e){
                this.initFileSenderListner();
                FileSenderListner.getInstance().setStatus(FileSenderListner.STATUS_IDLE);
                Log.e(TAG, e.getMessage(), e);
            }

            return lines;
        }

        public void onProgressUpdate(Integer... progress){
            FileSenderListner.getInstance().setRowsInFile(progress[0]);
        }

        public void onPostExecute(Integer lines){
            FileSenderListner.getInstance().setRowsInFile(lines);
            FileSenderListner.getInstance().setStatus(FileSenderListner.STATUS_IDLE);
        }

        private void initFileSenderListner(){
            FileSenderListner.getInstance().setRowsInFile(0);
            FileSenderListner.getInstance().setRowsSent(0);
        }
    }

    private void getFilePicker(){

        FilePickerBuilder.getInstance().setMaxCount(1)
                .setActivityTheme(R.style.FilePickerTheme)
                .addFileSupport(GrblUtils.implode(" | ", Constants.SUPPORTED_FILE_TYPES), Constants.SUPPORTED_FILE_TYPES)
                .enableDocSupport(false)
                .showFolderView(false)
                .pickFile(getActivity());

    }

    private Boolean hasExternalStorageReadPermission(){
        Boolean hasPermission = true;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(getActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                hasPermission = false;
            }
        }
        return hasPermission;
    }

    private void askExternalReadPermission(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, Constants.REQUEST_READ_PERMISSIONS);
        }else{
            EventBus.getDefault().post(new UiToastEvent(getString(R.string.no_external_read_permission)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if(requestCode == Constants.REQUEST_READ_PERMISSIONS){
            if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                getFilePicker();
            }else{
                EventBus.getDefault().post(new UiToastEvent(getString(R.string.no_external_read_permission)));
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBluetoothDisconnectEvent(BluetoothDisconnectEvent event){
        stopFileStreaming();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGrblErrorEvent(GrblErrorEvent event){
        stopFileStreaming();
    }

}