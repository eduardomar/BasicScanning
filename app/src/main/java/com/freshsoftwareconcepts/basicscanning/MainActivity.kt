package com.freshsoftwareconcepts.basicscanning

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.method.ScrollingMovementMethod
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.View.FOCUS_DOWN
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import com.symbol.emdk.EMDKManager
import com.symbol.emdk.EMDKManager.EMDKListener
import com.symbol.emdk.EMDKManager.FEATURE_TYPE
import com.symbol.emdk.EMDKResults
import com.symbol.emdk.barcode.*
import com.symbol.emdk.barcode.BarcodeManager.ConnectionState
import com.symbol.emdk.barcode.ScanDataCollection.ScanData
import com.symbol.emdk.barcode.Scanner
import com.symbol.emdk.barcode.Scanner.*
import com.symbol.emdk.barcode.StatusData.ScannerStates.*
import java.util.*

class MainActivity : AppCompatActivity(), EMDKListener, StatusListener, DataListener, BarcodeManager.ScannerConnectionListener, CompoundButton.OnCheckedChangeListener {
    private var emdkManager: EMDKManager? = null
    private var barcodeManager: BarcodeManager? = null
    private var scanner: Scanner? = null
    private var bContinuousMode = false
    val textViewData: TextView by lazy { this.findViewById<TextView>(R.id.textViewData) }
    val textViewStatus: TextView by lazy { this.findViewById<TextView>(R.id.textViewStatus) }

    val checkBoxEAN8: CheckBox by lazy { this.findViewById<CheckBox>(R.id.checkBoxEAN8) }
    val checkBoxEAN13: CheckBox by lazy { this.findViewById<CheckBox>(R.id.checkBoxEAN13) }
    val checkBoxCode39: CheckBox by lazy { this.findViewById<CheckBox>(R.id.checkBoxCode39) }
    val checkBoxCode128: CheckBox by lazy { this.findViewById<CheckBox>(R.id.checkBoxCode128) }
    val checkBoxContinuous: CheckBox by lazy { this.findViewById<CheckBox>(R.id.checkBoxContinuous) }

    val spinnerScannerDevices: Spinner by lazy { this.findViewById<Spinner>(R.id.spinnerScannerDevices) }
    val spinnerTriggers: Spinner by lazy { this.findViewById<Spinner>(R.id.spinnerTriggers) }

    val btnStartScan: Button? by lazy { this.findViewById<Button>(R.id.buttonStartScan) }
    val btnStopScan: Button? by lazy { this.findViewById<Button>(R.id.buttonStopScan) }

    private var deviceList: ArrayList<ScannerInfo>? = null

    private var scannerIndex = 0 // Keep the selected scanner
    private var defaultIndex = 0 // Keep the default scanner
    private var triggerIndex = 0
    private var dataLength = 0
    private var statusString = ""

    private val triggerStrings = arrayOf("HARD", "SOFT")

    fun setDefaultOrientation() {
        val windowManager: WindowManager = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation

        val dm: DisplayMetrics = DisplayMetrics()
        this.windowManager.defaultDisplay.getMetrics(dm)

        var width: Int = 0
        var height: Int = 0

        when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                width = dm.widthPixels
                height = dm.heightPixels
            }

            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                width = dm.heightPixels
                height = dm.widthPixels
            }
        }

        if (width > height) {
            this.setContentView(R.layout.activity_main_landscape)
        } else {
            this.setContentView(R.layout.activity_main)
        }
    }

    fun initScanner() {
        if (this.scanner == null) {
            if (this.deviceList != null && this.deviceList!!.size != 0) {
                this.scanner = this.barcodeManager!!.getDevice(this.deviceList!!.get(this.scannerIndex))
            } else {
                this.textViewStatus?.text = "Status: Failed to get the specified scanner device! Please close and restart the application."
                return
            }

            this.scanner?.let {
                it.addDataListener(this)
                it.addStatusListener(this)

                try {
                    it.enable()
                } catch (e: ScannerException) {
                    this.textViewStatus?.text = "Status: ${e.message}"
                }
            } ?: run {
                this.textViewStatus?.text = "Status: Failed to initialize the scanner device."
            }
        }
    }

    fun deInitScanner() {
        this.scanner?.let {
            try {
                it.cancelRead()
                it.disable()
            } catch (e: Exception) {
                this.textViewStatus?.text = "Status: ${e.message}"
            }

            try {
                it.removeDataListener(this)
                it.removeStatusListener(this)
            } catch (e: Exception) {
                this.textViewStatus?.text = "Status: ${e.message}"
            }

            try {
                it.release()
            } catch (e: Exception) {
                this.textViewStatus?.text = "Status: ${e.message}"
            }

            this.scanner = null
        }
    }

    inner class AsyncDataUpdate : AsyncTask<String, Void, String>() {
        override fun doInBackground(vararg params: String?): String {
            return params[0]!!
        }

        override fun onPostExecute(result: String?) {
            result?.let {
                if (this@MainActivity.dataLength++ > 100) { // Limpiamo el cache despues de 100 escaneos
                    this@MainActivity.textViewData?.text = ""
                    this@MainActivity.dataLength = 0
                }

                this@MainActivity.textViewData?.append("${it}\n")

                this@MainActivity.findViewById<ScrollView>(R.id.scrollView1).post(object : Runnable {
                    override fun run() {
                        this@MainActivity.findViewById<ScrollView>(R.id.scrollView1).fullScroll(FOCUS_DOWN)
                    }
                })
            }
        }
    }

    inner class AsyncStatusUpdate : AsyncTask<String, Void, String>() {
        override fun doInBackground(vararg params: String?): String {
            return params[0]!!
        }

        override fun onPostExecute(result: String?) {
            this@MainActivity.textViewStatus?.text = "Status: ${result}"
        }
    }

    inner class AsyncUiControlUpdate : AsyncTask<Boolean, Void, Boolean>() {
        override fun doInBackground(vararg params: Boolean?): Boolean {
            return params[0]!!
        }

        override fun onPostExecute(result: Boolean?) {
            result?.let {
                this@MainActivity.checkBoxEAN8?.isEnabled = it
                this@MainActivity.checkBoxEAN13?.isEnabled = it
                this@MainActivity.checkBoxCode39?.isEnabled = it
                this@MainActivity.checkBoxCode128?.isEnabled = it
                this@MainActivity.spinnerScannerDevices?.isEnabled = it
                this@MainActivity.spinnerTriggers?.isEnabled = it
            }
        }
    }

    fun addSpinnerScannerDevicesListener() {
        this.spinnerScannerDevices?.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (this@MainActivity.scannerIndex != position || this@MainActivity.scanner == null) {
                    this@MainActivity.scannerIndex = position
                    this@MainActivity.deInitScanner()
                    this@MainActivity.initScanner()
                    this@MainActivity.setTrigger()
                    this@MainActivity.setDecoders()
                }
            }
        }
    }

    fun addSpinnerTriggersListener() {
        this.spinnerTriggers?.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                this@MainActivity.triggerIndex = position
                this@MainActivity.setTrigger()
            }
        }
    }

    fun addStartScanButtonListener() {
        this.btnStartScan?.setOnClickListener {
            this.startScan()
        }
    }

    fun addStopScanButtonListener() {
        this.btnStopScan?.setOnClickListener {
            this.stopScan()
        }
    }

    fun addCheckBoxListener() {
        this.checkBoxContinuous?.setOnCheckedChangeListener { _, isChecked ->
            this.bContinuousMode = isChecked
        }
    }

    fun enumerateScannerDevices() {
        this.barcodeManager?.let { barcodeManager ->
            val friendlyNameList: ArrayList<String> = ArrayList<String>()
            var spinnerIndex: Int = 0

            this.deviceList = barcodeManager.supportedDevicesInfo as ArrayList<ScannerInfo>
            this.deviceList.let { lst ->
                if (lst != null && lst.size != 0) {
                    val i: Iterator<ScannerInfo> = lst.iterator()
                    while (i.hasNext()) {
                        val scnInfo: ScannerInfo = i.next()
                        friendlyNameList.add(scnInfo.friendlyName)
                        if (scnInfo.isDefaultScanner) {
                            this.defaultIndex = spinnerIndex
                        }
                        ++spinnerIndex
                    }
                } else {
                    this.textViewStatus?.setText("Status: Failed to get the list of supported scanner devices! Please close and restart the application.")
                }

                val spinnerAdapter: ArrayAdapter<String> = ArrayAdapter(this, android.R.layout.simple_spinner_item, friendlyNameList)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                this.spinnerScannerDevices?.adapter = spinnerAdapter
            }
        }
    }

    fun setTrigger() {
        this.initScanner()

        this.scanner?.let {
            when (this.triggerIndex) {
                0 -> it.triggerType = TriggerType.HARD
                1 -> it.triggerType = TriggerType.SOFT_ALWAYS
            }
        }
    }

    fun setDecoders() {
        this.initScanner()

        this.scanner?.let {
            if (it.isEnabled) {
                try {
                    val config: ScannerConfig = it.config
                    config.decoderParams.ean8.enabled = this.checkBoxEAN8!!.isChecked       // Set EAN8
                    config.decoderParams.ean13.enabled = this.checkBoxEAN13!!.isChecked     // Set EAN13
                    config.decoderParams.code39.enabled = this.checkBoxCode39!!.isChecked   // Set Code39
                    config.decoderParams.code128.enabled = this.checkBoxCode128!!.isChecked // Set Code128

                    it.config = config
                } catch (e: ScannerException) {
                    this.textViewStatus?.text = "Status: ${e.message}"
                }
            }
        }
    }

    fun startScan() {
        this.initScanner()

        this.scanner?.let {
            try {
                if (it.isEnabled) {
                    it.read() // Enviar una nueva lectura
                    this.bContinuousMode = this.checkBoxContinuous!!.isChecked
                    AsyncUiControlUpdate().execute(false);
                } else {
                    this.textViewStatus?.text = "Status: Scanner is not enabled"
                }
            } catch (e: ScannerException) {
                this.textViewStatus?.text = "Status: ${e.message}"
            }
        }
    }

    fun stopScan() {
        this.scanner?.let {
            try {
                this.bContinuousMode = false // Reiniciar bandera
                it.cancelRead() // Cancelar lectura pendiente
                AsyncUiControlUpdate().execute(true);
            } catch (e: ScannerException) {
                this.textViewStatus?.text = "Status: ${e.message}"
            }
        }
    }

    fun populateTriggers() {
        val spinnerAdapter: ArrayAdapter<String> = ArrayAdapter(this, android.R.layout.simple_spinner_item, this.triggerStrings)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        this.spinnerTriggers?.adapter = spinnerAdapter
        this.spinnerTriggers?.setSelection(this.triggerIndex)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.deviceList = ArrayList<ScannerInfo>()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        setDefaultOrientation()

        try {
            val results = EMDKManager.getEMDKManager(applicationContext, this)
            if (results.statusCode != EMDKResults.STATUS_CODE.SUCCESS) {
                this.textViewStatus?.setText("Status: " + "EMDKManager object request failed!")
                return
            }
        } catch (e: Exception) {
            this.textViewStatus?.setText("Status: " + "EMDKManager object request failed! ${e.message}")
            e.printStackTrace()
        }

        this.checkBoxEAN8?.setOnCheckedChangeListener(this)
        this.checkBoxEAN13?.setOnCheckedChangeListener(this)
        this.checkBoxCode39?.setOnCheckedChangeListener(this)
        this.checkBoxCode128?.setOnCheckedChangeListener(this)

        this.addSpinnerScannerDevicesListener()
        this.populateTriggers()
        this.addSpinnerTriggersListener()
        this.addStartScanButtonListener()
        this.addStopScanButtonListener()
        this.addCheckBoxListener()

        this.textViewData?.setSelected(true)
        this.textViewData?.setMovementMethod(ScrollingMovementMethod())
    }

    override fun onDestroy() {
        super.onDestroy()

        // Desinicializar scanner
        deInitScanner()

        // Remover el ConnectionListener
        this.barcodeManager?.let {
            it.removeConnectionListener(this)
            this.barcodeManager = null
        }

        // Liberar todos los recursos
        this.emdkManager?.let {
            it.release()
            this.emdkManager = null
        }
    }

    override fun onPause() {
        super.onPause()

        // Desinicializar scanner
        deInitScanner()

        // Remover el ConnectionListener
        this.barcodeManager?.let {
            it.removeConnectionListener(this)
            this.barcodeManager = null
            this.deviceList = null
        }

        // Liberar el administrador de recursos de código de barras.
        this.emdkManager?.let {
            it.release(FEATURE_TYPE.BARCODE)
        }
    }

    override fun onResume() {
        super.onResume()

        // Adquirir el administrador de recursos de código de barras.
        this.emdkManager?.let { emdkManager ->
            this.barcodeManager = emdkManager.getInstance(FEATURE_TYPE.BARCODE) as BarcodeManager

            // Agregar el ConnectionListener
            this.barcodeManager?.let { barcodeManager ->
                barcodeManager.addConnectionListener(this)
            }

            // Enumerar dispositivos escáner
            this.enumerateScannerDevices()

            // Establecer escáner seleccionado
            this.spinnerScannerDevices.setSelection(scannerIndex);

            // Inicializar escáner
            this.initScanner();
            this.setTrigger();
            this.setDecoders();

            emdkManager
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        this.menuInflater.inflate(R.menu.main, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        val id: Int? = item?.itemId

        if (id == R.id.action_settings) {
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onOpened(emdkManager: EMDKManager?) {
        this.textViewStatus?.setText("Status: EMDK open success!")

        this.emdkManager = emdkManager

        // Adquirir el administrador de recursos de código de barras.
        this.barcodeManager = this.emdkManager?.getInstance(FEATURE_TYPE.BARCODE) as BarcodeManager

        // Agregar el ConnectionListener
        this.barcodeManager?.let {
            it.addConnectionListener(this)
        }

        // Enumerar dispositivos escáner
        this.enumerateScannerDevices()

        // Establecer escáner seleccionado
        this.spinnerScannerDevices.setSelection(scannerIndex);
    }

    override fun onClosed() {
        // Remover el ConnectionListener
        this.barcodeManager?.let {
            it.removeConnectionListener(this)
            this.barcodeManager = null
        }

        // Liberar todos los recursos
        this.emdkManager?.let {
            it.release()
            this.emdkManager = null
        }

        textViewStatus?.setText("Status: EMDK closed unexpectedly! Please close and restart the application.")
    }

    override fun onStatus(statusData: StatusData?) {
        statusData?.let {
            when (it.state) {
                IDLE -> {
                    this.statusString = "${statusData.friendlyName} is enabled and idle..."
                    AsyncStatusUpdate().execute(this.statusString)

                    if (this.bContinuousMode) {
                        try {
                            try {
                                Thread.sleep(100)
                            } catch (e: InterruptedException) {
                                e.printStackTrace()
                            }

                            this.scanner?.read()
                        } catch (e: ScannerException) {
                            this.statusString = e.message!!
                            AsyncStatusUpdate().execute(this.statusString)
                        }
                    }

                    AsyncUiControlUpdate().execute(true)
                }

                WAITING -> {
                    this.statusString = "Scanner is waiting for trigger press..."
                    AsyncStatusUpdate().execute(statusString)
                    AsyncUiControlUpdate().execute(false)
                }

                SCANNING -> {
                    this.statusString = "Scanning..."
                    AsyncStatusUpdate().execute(statusString)
                    AsyncUiControlUpdate().execute(false)
                }

                DISABLED -> {
                    this.statusString = statusData.getFriendlyName() + " is disabled."
                    AsyncStatusUpdate().execute(statusString)
                    AsyncUiControlUpdate().execute(true)
                }

                ERROR -> {
                    this.statusString = "An error has occurred."
                    AsyncStatusUpdate().execute(statusString)
                    AsyncUiControlUpdate().execute(true)
                }
            }

            it
        }
    }

    override fun onData(scanDataCollection: ScanDataCollection?) {
        scanDataCollection?.let {
            if (it.result == ScannerResults.SUCCESS) {
                for (data: ScanData in it.scanData) {
                    AsyncDataUpdate().execute(data.data)
                }
            }
        }
    }

    override fun onConnectionChange(scannerInfo: ScannerInfo?, connectionState: BarcodeManager.ConnectionState?) {
        var status = ""
        var scannerName = ""
        var statusExtScanner = connectionState.toString()
        var scannerNameExtScanner = scannerInfo?.friendlyName

        if (this.deviceList?.size != 0) {
            scannerName = this.deviceList!!.get(this.scannerIndex).friendlyName
        }

        if (scannerName.equals(scannerNameExtScanner)) {
            when (connectionState) {
                ConnectionState.CONNECTED -> {
                    this.deInitScanner()
                    this.initScanner()
                    this.setTrigger()
                    this.setDecoders()
                }

                ConnectionState.DISCONNECTED -> {
                    this.deInitScanner();
                    AsyncUiControlUpdate().execute(true);
                }
            }

            status = "${scannerNameExtScanner}: ${statusExtScanner}"
        } else {
            status = "${statusString} ${scannerNameExtScanner}: ${statusExtScanner}"
        }

        AsyncStatusUpdate().execute(status)
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        this.setDecoders()
    }
}