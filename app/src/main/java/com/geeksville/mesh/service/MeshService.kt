package com.geeksville.mesh.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_MIN
import com.geeksville.android.Logging
import com.geeksville.mesh.*
import com.geeksville.mesh.MeshProtos.MeshPacket
import com.geeksville.mesh.MeshProtos.ToRadio
import com.geeksville.util.exceptionReporter
import com.geeksville.util.reportException
import com.geeksville.util.toOneLineString
import com.geeksville.util.toRemoteExceptions
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.protobuf.ByteString
import java.nio.charset.Charset


class RadioNotConnectedException() : Exception("Can't find radio")


/**
 * Handles all the communication with android apps.  Also keeps an internal model
 * of the network state.
 *
 * Note: this service will go away once all clients are unbound from it.
 */
class MeshService : Service(), Logging {

    companion object : Logging {

        /// Intents broadcast by MeshService
        const val ACTION_RECEIVED_DATA = "$prefix.RECEIVED_DATA"
        const val ACTION_NODE_CHANGE = "$prefix.NODE_CHANGE"
        const val ACTION_MESH_CONNECTED = "$prefix.MESH_CONNECTED"

        class IdNotFoundException(id: String) : Exception("ID not found $id")
        class NodeNumNotFoundException(id: Int) : Exception("NodeNum not found $id")
        class NotInMeshException() : Exception("We are not yet in a mesh")

        /// Helper function to start running our service, returns the intent used to reach it
        /// or null if the service could not be started (no bluetooth or no bonded device set)
        fun startService(context: Context): Intent? {
            if (RadioInterfaceService.getBondedDeviceAddress(context) == null) {
                warn("No mesh radio is bonded, not starting service")
                return null
            } else {
                // bind to our service using the same mechanism an external client would use (for testing coverage)
                // The following would work for us, but not external users
                //val intent = Intent(this, MeshService::class.java)
                //intent.action = IMeshService::class.java.name
                val intent = Intent()
                intent.setClassName(
                    "com.geeksville.mesh",
                    "com.geeksville.mesh.service.MeshService"
                )

                // Before binding we want to explicitly create - so the service stays alive forever (so it can keep
                // listening for the bluetooth packets arriving from the radio.  And when they arrive forward them
                // to Signal or whatever.

                logAssert(
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }) != null
                )

                return intent
            }
        }
    }

    /// A mapping of receiver class name to package name - used for explicit broadcasts
    private val clientPackages = mutableMapOf<String, String>()

    private var radioService: IRadioInterfaceService? = null

    /*
    see com.geeksville.mesh broadcast intents
    // RECEIVED_OPAQUE  for data received from other nodes
    // NODE_CHANGE  for new IDs appearing or disappearing
    // ACTION_MESH_CONNECTED for losing/gaining connection to the packet radio (note, this is not
    the same as RadioInterfaceService.RADIO_CONNECTED_ACTION, because it implies we have assembled a valid
    node db.
     */

    private fun explicitBroadcast(intent: Intent) {
        sendBroadcast(intent) // We also do a regular (not explicit broadcast) so any context-registered rceivers will work
        clientPackages.forEach {
            intent.setClassName(it.value, it.key)
            sendBroadcast(intent)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            var l = locationResult.lastLocation

            // Docs say lastLocation should always be !null if there are any locations, but that's not the case
            if (l == null) {
                // try to only look at the accurate locations
                val locs =
                    locationResult.locations.filter { !it.hasAccuracy() || it.accuracy < 200 }
                l = locs.lastOrNull()
            }
            if (l != null) {
                info("got location $l")
                if (l.hasAccuracy() && l.accuracy >= 200) // if more than 200 meters off we won't use it
                    warn("accuracy ${l.accuracy} is too poor to use")
                else {
                    sendPosition(l.latitude, l.longitude, l.altitude.toInt())
                }
            }

        }
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null

    /**
     * start our location requests
     *
     * per https://developer.android.com/training/location/change-location-settings
     */
    @SuppressLint("MissingPermission")
    private fun startLocationRequests() {
        val request = LocationRequest.create().apply {
            interval =
                60 * 1000 // FIXME, do more like once every 5 mins while we are connected to our radio _and_ someone else is in the mesh

            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val builder = LocationSettingsRequest.Builder().addLocationRequest(request)
        val locationClient = LocationServices.getSettingsClient(this)
        val locationSettingsResponse = locationClient.checkLocationSettings(builder.build())

        locationSettingsResponse.addOnSuccessListener {
            debug("We are now successfully listening to the GPS")
        }

        locationSettingsResponse.addOnFailureListener { exception ->
            error("Failed to listen to GPS")
            if (exception is ResolvableApiException) {
                exceptionReporter {
                    // Location settings are not satisfied, but this can be fixed
                    // by showing the user a dialog.

                    // FIXME
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    /* exception.startResolutionForResult(
                        this@MainActivity,
                        REQUEST_CHECK_SETTINGS
                    ) */
                }
            } else
                reportException(exception)
        }

        val client = LocationServices.getFusedLocationProviderClient(this)


        // FIXME - should we use Looper.myLooper() in the third param per https://github.com/android/location-samples/blob/432d3b72b8c058f220416958b444274ddd186abd/LocationUpdatesForegroundService/app/src/main/java/com/google/android/gms/location/sample/locationupdatesforegroundservice/LocationUpdatesService.java
        client.requestLocationUpdates(request, locationCallback, null)

        fusedLocationClient = client
    }

    private fun stopLocationRequests() {
        fusedLocationClient?.removeLocationUpdates(locationCallback)
        fusedLocationClient = null
    }

    /**
     * The RECEIVED_OPAQUE:
     * Payload will be the raw bytes which were contained within a MeshPacket.Opaque field
     * Sender will be a user ID string
     * Type will be the Data.Type enum code for this payload
     */
    private fun broadcastReceivedData(senderId: String, payload: ByteArray, typ: Int) {
        val intent = Intent(ACTION_RECEIVED_DATA)
        intent.putExtra(EXTRA_SENDER, senderId)
        intent.putExtra(EXTRA_PAYLOAD, payload)
        intent.putExtra(EXTRA_TYP, typ)
        explicitBroadcast(intent)
    }

    private fun broadcastNodeChange(info: NodeInfo) {
        debug("Broadcasting node change $info")
        val intent = Intent(ACTION_NODE_CHANGE)

        /*
        if (info.user == null)
            info.user = MeshUser("x", "y", "z")

        if (info.position == null)
            info.position = Position(1.5, 1.6, 3)

        */

        intent.putExtra(EXTRA_NODEINFO, info)
        explicitBroadcast(intent)
    }

    /// Safely access the radio service, if not connected an exception will be thrown
    private val connectedRadio: IRadioInterfaceService
        get() {
            val s = radioService
            if (s == null || !isConnected)
                throw RadioNotConnectedException()

            return s
        }

    /// Send a command/packet to our radio.  But cope with the possiblity that we might start up
    /// before we are fully bound to the RadioInterfaceService
    private fun sendToRadio(p: ToRadio.Builder) {
        val b = p.build().toByteArray()

        connectedRadio.sendToRadio(b)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private val radioConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val m = IRadioInterfaceService.Stub.asInterface(
                service
            )
            radioService = m
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "my_service"
        val channelName = "My Background Service"
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_HIGH
        )
        chan.lightColor = Color.BLUE
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun startForeground() {

        // val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setPriority(PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            //.setContentTitle("Meshtastic") // leave this off for now so our notification looks smaller
            //.setContentText("Listening for mesh...")
            .build()
        startForeground(101, notification)
    }

    override fun onCreate() {
        super.onCreate()

        info("Creating mesh service")

        /*
        // This intent will be used if the user clicks on the item in the status bar
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            notificationIntent, 0
        )

        val notification: Notification = NotificationCompat.Builder(this)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Meshtastic")
            .setContentText("Listening for mesh...")
            .setContentIntent(pendingIntent).build()

        // We are required to call this within a few seconds of create
        startForeground(1337, notification)

         */
        startForeground()

        // we listen for messages from the radio receiver _before_ trying to create the service
        val filter = IntentFilter()
        filter.addAction(RadioInterfaceService.RECEIVE_FROMRADIO_ACTION)
        filter.addAction(RadioInterfaceService.RADIO_CONNECTED_ACTION)
        registerReceiver(radioInterfaceReceiver, filter)

        // We in turn need to use the radiointerface service
        val intent = Intent(this, RadioInterfaceService::class.java)
        // intent.action = IMeshService::class.java.name
        logAssert(bindService(intent, radioConnection, Context.BIND_AUTO_CREATE))

        // the rest of our init will happen once we are in radioConnection.onServiceConnected
    }


    override fun onDestroy() {
        info("Destroying mesh service")
        unregisterReceiver(radioInterfaceReceiver)
        unbindService(radioConnection)
        radioService = null

        super.onDestroy()
    }

    ///
    /// BEGINNING OF MODEL - FIXME, move elsewhere
    ///

    /// special broadcast address
    val NODENUM_BROADCAST = 255

    // MyNodeInfo sent via special protobuf from radio
    data class MyNodeInfo(val myNodeNum: Int, val hasGPS: Boolean)

    var myNodeInfo: MyNodeInfo? = null

    /// Is our radio connected to the phone?
    private var isConnected = false

    // The database of active nodes, index is the node number
    private val nodeDBbyNodeNum = mutableMapOf<Int, NodeInfo>()

    /// The database of active nodes, index is the node user ID string
    /// NOTE: some NodeInfos might be in only nodeDBbyNodeNum (because we don't yet know
    /// an ID).  But if a NodeInfo is in both maps, it must be one instance shared by
    /// both datastructures.
    private val nodeDBbyID = mutableMapOf<String, NodeInfo>()

    ///
    /// END OF MODEL
    ///

    /// Map a nodenum to a node, or throw an exception if not found
    private fun toNodeInfo(n: Int) = nodeDBbyNodeNum[n] ?: throw NodeNumNotFoundException(
        n
    )

    /// Map a nodenum to the nodeid string, or throw an exception if not present
    private fun toNodeID(n: Int) = toNodeInfo(n).user?.id

    /// given a nodenum, return a db entry - creating if necessary
    private fun getOrCreateNodeInfo(n: Int) =
        nodeDBbyNodeNum.getOrPut(n) { -> NodeInfo(n) }

    /// Map a userid to a node/ node num, or throw an exception if not found
    private fun toNodeInfo(id: String) =
        nodeDBbyID[id]
            ?: throw IdNotFoundException(
                id
            )

    // ?: getOrCreateNodeInfo(10) // FIXME hack for now -  throw IdNotFoundException(id)

    private fun toNodeNum(id: String) = toNodeInfo(id).num

    /// A helper function that makes it easy to update node info objects
    private fun updateNodeInfo(nodeNum: Int, updatefn: (NodeInfo) -> Unit) {
        val info = getOrCreateNodeInfo(nodeNum)
        updatefn(info)

        // This might have been the first time we know an ID for this node, so also update the by ID map
        val userId = info.user?.id.orEmpty()
        if (userId.isNotEmpty())
            nodeDBbyID[userId] = info

        // parcelable is busted
        broadcastNodeChange(info)
    }

    /// Generate a new mesh packet builder with our node as the sender, and the specified node num
    private fun newMeshPacketTo(idNum: Int) = MeshPacket.newBuilder().apply {
        if (myNodeInfo == null)
            throw RadioNotConnectedException()

        from = myNodeInfo!!.myNodeNum
        to = idNum
    }

    /// Generate a new mesh packet builder with our node as the sender, and the specified recipient
    private fun newMeshPacketTo(id: String) = newMeshPacketTo(toNodeNum(id))

    // Helper to make it easy to build a subpacket in the proper protobufs
    private fun buildMeshPacket(
        destId: String,
        initFn: MeshProtos.SubPacket.Builder.() -> Unit
    ): MeshPacket = newMeshPacketTo(destId).apply {
        payload = MeshProtos.SubPacket.newBuilder().also {
            initFn(it)
        }.build()
    }.build()

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedData(fromNum: Int, data: MeshProtos.Data) {
        val bytes = data.payload.toByteArray()
        val fromId = toNodeID(fromNum)

        /// the sending node ID if possible, else just its number
        val fromString = fromId ?: fromId.toString()

        fun forwardData() {
            if (fromId == null)
                warn("Ignoring data from $fromNum because we don't yet know its ID")
            else {
                debug("Received data from $fromId ${bytes.size}")
                broadcastReceivedData(fromId, bytes, data.typValue)
            }
        }

        when (data.typValue) {
            MeshProtos.Data.Type.CLEAR_TEXT_VALUE -> {
                debug(
                    "FIXME - don't long this: Received CLEAR_TEXT from $fromString: ${bytes.toString(
                        Charset.forName("UTF-8")
                    )}"
                )
                forwardData()
            }

            MeshProtos.Data.Type.CLEAR_READACK_VALUE ->
                warn(
                    "TODO ignoring CLEAR_READACK from $fromString"
                )

            MeshProtos.Data.Type.SIGNAL_OPAQUE_VALUE ->
                forwardData()

            else -> TODO()
        }
    }

    /// Update our DB of users based on someone sending out a User subpacket
    private fun handleReceivedUser(fromNum: Int, p: MeshProtos.User) {
        updateNodeInfo(fromNum) {
            it.user = MeshUser(
                p.id,
                p.longName,
                p.shortName
            )
        }
    }

    /// Update our DB of users based on someone sending out a Position subpacket
    private fun handleReceivedPosition(fromNum: Int, p: MeshProtos.Position) {
        updateNodeInfo(fromNum) {
            it.position = Position(
                p.latitude,
                p.longitude,
                p.altitude
            )
        }
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedMeshPacket(packet: MeshPacket) {
        val fromNum = packet.from

        // FIXME, perhaps we could learn our node ID by looking at any to packets the radio
        // decided to pass through to us (except for broadcast packets)
        val toNum = packet.to

        val p = packet.payload

        // Update our last seen based on any valid timestamps
        if (packet.rxTime != 0) {
            updateNodeInfo(fromNum) {
                it.lastSeen = packet.rxTime
            }
        }

        when (p.variantCase.number) {
            MeshProtos.SubPacket.POSITION_FIELD_NUMBER ->
                handleReceivedPosition(fromNum, p.position)

            MeshProtos.SubPacket.DATA_FIELD_NUMBER ->
                handleReceivedData(fromNum, p.data)

            MeshProtos.SubPacket.USER_FIELD_NUMBER ->
                handleReceivedUser(fromNum, p.user)
            else -> TODO("Unexpected SubPacket variant")
        }
    }


    /// Called when we gain/lose connection to our radio
    private fun onConnectionChanged(c: Boolean) {
        debug("onConnectionChanged connected=$c")
        isConnected = c
        if (c) {
            // Do our startup init

            // FIXME - don't do this until after we see that the radio is connected to the phone
            //val sim = SimRadio(this@MeshService)
            //sim.start() // Fake up our node id info and some past packets from other nodes

            val myInfo = MeshProtos.MyNodeInfo.parseFrom(
                connectedRadio.readMyNode()
            )


            val mynodeinfo = MyNodeInfo(myInfo.myNodeNum, myInfo.hasGps)
            myNodeInfo = mynodeinfo

            // Ask for the current node DB
            connectedRadio.restartNodeInfo()

            // read all the infos until we get back null
            var infoBytes = connectedRadio.readNodeInfo()
            while (infoBytes != null) {
                val info =
                    MeshProtos.NodeInfo.parseFrom(infoBytes)
                debug("Received initial nodeinfo $info")

                // Just replace/add any entry
                updateNodeInfo(info.num) {
                    if (info.hasUser())
                        it.user =
                            MeshUser(
                                info.user.id,
                                info.user.longName,
                                info.user.shortName
                            )

                    if (info.hasPosition())
                        it.position = Position(
                            info.position.latitude,
                            info.position.longitude,
                            info.position.altitude
                        )

                    it.lastSeen = info.lastSeen
                }

                // advance to next
                infoBytes = connectedRadio.readNodeInfo()
            }

            // we don't ask for GPS locations from android if our device has a built in GPS
            if (!mynodeinfo.hasGPS)
                startLocationRequests()
            else
                debug("Our radio has a built in GPS, so not reading GPS in phone")
        } else {
            // lost radio connection, therefore no need to keep listening to GPS
            stopLocationRequests()
        }
    }

    /**
     * Receives messages from our BT radio service and processes them to update our model
     * and send to clients as needed.
     */
    private val radioInterfaceReceiver = object : BroadcastReceiver() {

        // Important to never throw exceptions out of onReceive
        override fun onReceive(context: Context, intent: Intent) = exceptionReporter {

            debug("Received broadcast ${intent.action}")
            when (intent.action) {
                RadioInterfaceService.RADIO_CONNECTED_ACTION -> {
                    onConnectionChanged(intent.getBooleanExtra(EXTRA_CONNECTED, false))

                    // forward the connection change message to anyone who is listening to us. but change the action
                    // to prevent an infinite loop from us receiving our own broadcast. ;-)
                    intent.action = ACTION_MESH_CONNECTED
                    explicitBroadcast(intent)
                }

                RadioInterfaceService.RECEIVE_FROMRADIO_ACTION -> {
                    val proto =
                        MeshProtos.FromRadio.parseFrom(
                            intent.getByteArrayExtra(
                                EXTRA_PAYLOAD
                            )!!
                        )
                    info("Received from radio service: ${proto.toOneLineString()}")
                    when (proto.variantCase.number) {
                        MeshProtos.FromRadio.PACKET_FIELD_NUMBER -> handleReceivedMeshPacket(
                            proto.packet
                        )

                        else -> TODO("Unexpected FromRadio variant")
                    }
                }

                else -> TODO("Unexpected radio interface broadcast")
            }
        }
    }

    /// Send a position (typically from our built in GPS) into the mesh
    private fun sendPosition(lat: Double, lon: Double, alt: Int) {
        debug("Sending our position into mesh lat=$lat, lon=$lon, alt=$alt")

        val destNum = NODENUM_BROADCAST

        val position = MeshProtos.Position.newBuilder().also {
            it.latitude = lat
            it.longitude = lon
            it.altitude = alt
        }.build()

        // encapsulate our payload in the proper protobufs and fire it off
        val packet = newMeshPacketTo(destNum)

        packet.payload = MeshProtos.SubPacket.newBuilder().also {
            it.position = position
        }.build()

        // Also update our own map for our nodenum, by handling the packet just like packets from other users
        handleReceivedPosition(myNodeInfo!!.myNodeNum, position)

        // send the packet into the mesh
        sendToRadio(ToRadio.newBuilder().apply {
            this.packet = packet.build()
        })
    }

    private val binder = object : IMeshService.Stub() {
        // Note: bound methods don't get properly exception caught/logged, so do that with a wrapper
        // per https://blog.classycode.com/dealing-with-exceptions-in-aidl-9ba904c6d63
        override fun subscribeReceiver(packageName: String, receiverName: String) =
            toRemoteExceptions {
                clientPackages[receiverName] = packageName
            }

        override fun setOwner(myId: String?, longName: String, shortName: String) =
            toRemoteExceptions {
                debug("SetOwner $myId : $longName : $shortName")

                val user = MeshProtos.User.newBuilder().also {
                    if (myId != null)  // Only set the id if it was provided
                        it.id = myId
                    it.longName = longName
                    it.shortName = shortName
                }.build()

                // Also update our own map for our nodenum, by handling the packet just like packets from other users
                if (myNodeInfo != null) {
                    handleReceivedUser(myNodeInfo!!.myNodeNum, user)
                }

                // set my owner info
                connectedRadio.writeOwner(user.toByteArray())
            }

        override fun sendData(destId: String, payloadIn: ByteArray, typ: Int) =
            toRemoteExceptions {
                info("sendData $destId <- ${payloadIn.size} bytes")

                // encapsulate our payload in the proper protobufs and fire it off
                val packet = buildMeshPacket(destId) {
                    data = MeshProtos.Data.newBuilder().also {
                        it.typ =
                            MeshProtos.Data.Type.SIGNAL_OPAQUE
                        it.payload = ByteString.copyFrom(payloadIn)
                    }.build()
                }

                sendToRadio(ToRadio.newBuilder().apply {
                    this.packet = packet
                })
            }

        override fun getRadioConfig(): ByteArray = toRemoteExceptions {
            connectedRadio.readRadioConfig()
        }

        override fun setRadioConfig(payload: ByteArray) = toRemoteExceptions {
            connectedRadio.writeRadioConfig(payload)
        }

        override fun getNodes(): Array<NodeInfo> = toRemoteExceptions {
            val r = nodeDBbyID.values.toTypedArray()
            info("in getOnline, count=${r.size}")
            // return arrayOf("+16508675309")
            r
        }

        override fun isConnected(): Boolean = toRemoteExceptions {
            val r = this@MeshService.isConnected
            info("in isConnected=$r")
            r
        }
    }
}