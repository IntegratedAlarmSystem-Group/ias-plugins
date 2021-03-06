package org.eso.ias.contrib.plugin.visualinspection;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eso.ias.heartbeat.HbProducer;
import org.eso.ias.heartbeat.publisher.HbKafkaProducer;
import org.eso.ias.heartbeat.serializer.HbJsonSerializer;
import org.eso.ias.plugin.Plugin;
import org.eso.ias.plugin.PluginException;
import org.eso.ias.plugin.Sample;
import org.eso.ias.plugin.config.PluginConfig;
import org.eso.ias.plugin.config.PluginConfigException;
import org.eso.ias.plugin.config.PluginConfigFileReader;
import org.eso.ias.plugin.publisher.MonitorPointSender;
import org.eso.ias.plugin.publisher.PublisherException;
import org.eso.ias.plugin.publisher.impl.KafkaPublisher;
import org.eso.ias.types.IASTypes;
import org.eso.ias.types.OperationalMode;
import org.eso.ias.utils.ISO8601Helper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A plugin that gets monitor points and alarms
 * from a UDP socket.
 *
 * Strings received from the socket are pushed in the {@link #receivedStringQueue}
 * queue and processed by a dedicated thread to decouple reading
 * from processing and better cope with spikes.
 *
 * @author acaproni
 *
 */
public class VisualInspectionPlugin implements Runnable {

	/**
	 * The default file name to read
	 */
	private static final String DEFAULT_FILENAME = "inspections.json";

	/**
	 * The plugin to filter and send data to the
	 * BSDB
	 */
	private final Plugin plugin;

	/**
	 * The Json File to read
	 */
	private File jsonFile;

	/**
	 * The list of registries of type {@link WeatherStationInspectionRegistry}
	 */
	private List<WeatherStationInspectionRegistry> registries = null;

	/**
	 * The UDP port to receive messages
	 */
	public final int udpPort;

	/**
	 * The UDP socket
	 */
	private DatagramSocket udpSocket;

	/**
	 * The thread getting strings from the UDP socket
	 * and pushing them in the buffer
	 */
	private volatile Thread udpRecvThread;

	/**
	 * The thread getting strings from the buffer and
	 * sending them to the plugin
	 */
	private volatile Thread stringProcessorThread;

	/**
	 * Signal the thread to terminate
	 */
	private final AtomicBoolean terminateThread = new AtomicBoolean(false);

	/**
	 * The logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(VisualInspectionPlugin.class);

	/**
	 * The latch to be notified about termination
	 */
	private final CountDownLatch done = new CountDownLatch(1);

	/**
	 * The max size of the buffer
	 */
	public static final int receivedStringBufferSize = 2048;

	/**
	 * The mapper to convert received strings into {@link MessageDao}
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * The buffer of strings received from the socket
	 */
	private final LinkedBlockingDeque<String> receivedStringQueue = new LinkedBlockingDeque<>(receivedStringBufferSize);

	/**
	 * Constructor
	 *
	 * @param config the configuration of the plugin
	 * @param sender the publisher of monitor points to the BSDB
	 * @param hbProducer the sender of heartbeats
	 * @param jsonFilePath path to the file to read
	 */
	public VisualInspectionPlugin(
			PluginConfig config,
			MonitorPointSender sender,
			HbProducer hbProducer,
			String jsonFilePath) throws SocketException {
		Objects.requireNonNull(config);
		Objects.requireNonNull(sender);
		Objects.requireNonNull(hbProducer);
		plugin = new Plugin(config,sender,hbProducer);
		jsonFile = new File(jsonFilePath);
		if (!jsonFile.exists()) {
			logger.error("Error opening the json file: "+jsonFilePath);
			System.exit(-6);
		}
		if (jsonFile.exists()){
			try {
				registries = Arrays.asList(MAPPER.readValue(jsonFile, WeatherStationInspectionRegistry[].class));
			} catch (IOException e) {
				logger.error("Error parsing a registries from the json file {}",jsonFilePath, e);
				System.exit(-6);
			}
			for (int i = 0; i < registries.size(); i++) {
				System.out.println("  registries["+i+"] = " + registries.get(i));
			}

		}
		udpPort = 0;
	}

	/**
	 * Print the usage string
	 *
	 * @param options The options expected in the command line
	 */
	private static void printUsage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp( "VisualInspectionPlugin", options );
	}

	/**
	 * The main to start the plugin
	 */
	public static void main(String[] args) {
    VisualInspectionPlugin.logger.info("**** Starting VisualInspectionPlugin");

		// Use apache CLI for command line parsing
		Options options = new Options();
		options.addOption("k","kafka-broker",true,"Kafka Broker (server:port)");
		options.addOption("f","file-path",true,"Path of the file to read");
		options.addOption("c","config-file", true,"Plugin configuration file");



		CommandLineParser parser = new DefaultParser();
		CommandLine cmd=null;
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException pe) {
			logger.error("Error parsing the comamnd line: "+pe.getMessage());
			printUsage(options);
			System.exit(-1);
		}

		String kafkaBroker = null;
		if (cmd.hasOption("k")) {
			kafkaBroker = cmd.getOptionValue("k");
			VisualInspectionPlugin.logger.info("Kafka broker obtained from command line: {}",kafkaBroker);
		}

		String jsonFilePath = null;
		if (cmd.hasOption("f")) {
			jsonFilePath = cmd.getOptionValue("f");
			VisualInspectionPlugin.logger.info("Filepath to read obtained from command line: {}",jsonFilePath);
		}

		if (!cmd.hasOption("c")) {
			System.err.println("Configuration file missing");
			printUsage(options);
			System.exit(-4);
		}
		String fileName = cmd.getOptionValue("c");
		logger.info("Configuration file name {}",fileName);

		PluginConfig pluginConfig = null;
		try  {
			BufferedReader reader = new BufferedReader(new FileReader(fileName));
			PluginConfigFileReader configFileReader= new PluginConfigFileReader(reader,fileName);
			Future<PluginConfig> pluginConfigFuture = configFileReader.getPluginConfig();
			pluginConfig = pluginConfigFuture.get(1, TimeUnit.MINUTES);
		} catch (Exception e) {
			logger.error("Error reading configuration file {}",fileName,e);
			printUsage(options);
			System.exit(-5);
		}

		if (kafkaBroker == null) {
			kafkaBroker = pluginConfig.getSinkServer()+":"+pluginConfig.getSinkPort();
			VisualInspectionPlugin.logger.info("Kafka broker read from configuration file: {}",kafkaBroker);
		}

		if (jsonFilePath == null) {
			try  {
				jsonFilePath = pluginConfig.getProperty("input-file").get().getValue();
			} catch (Exception e) {
				logger.error("Error reading the path to the input-file from the configuration file {}",fileName, e);
				printUsage(options);
				System.exit(-5);
			}
			VisualInspectionPlugin.logger.info("Input file read from configuration file: {}",jsonFilePath);
		}

		MonitorPointSender mpSender = new KafkaPublisher(
				pluginConfig.getId(),
				pluginConfig.getMonitoredSystemId(),
				pluginConfig.getSinkServer(),
				pluginConfig.getSinkPort(),
				Plugin.getScheduledExecutorService());

		HbProducer hbProducer = new HbKafkaProducer(pluginConfig.getId()+"HBSender", kafkaBroker, new HbJsonSerializer());

		VisualInspectionPlugin visualInspectionPlugin = null;
		try {
			visualInspectionPlugin = new VisualInspectionPlugin(pluginConfig, mpSender, hbProducer, jsonFilePath);
		} catch (Exception e) {
			VisualInspectionPlugin.logger.error("The VisualInspectionPlugin failed to build",e);
			System.exit(-6);
		}

		// CountDownLatch latch = null;
		// try {
		// 	latch = visualInspectionPlugin.setUp();
		// } catch(PluginException pe) {
		// 	VisualInspectionPlugin.logger.error("The VisualInspectionPlugin failed to start",pe);
		// 	System.exit(-7);
		// }
		// try {
		// 	latch.await();
		// } catch (InterruptedException ie) {
		// 	VisualInspectionPlugin.logger.error("VisualInspectionPlugin interrupted",ie);
		// }
		// VisualInspectionPlugin.logger.info("Done.");

	}

	@Override
	public void run() {
		// The buffer
		System.out.println("!!!!! RUN !!!!!");
		// byte[] buffer = new byte[2048];
		// logger.debug("UDP loop thread started");
		// // The loop to get monitor from the socket
		// while (!terminateThread.get()) {
		// 	DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		// 	try {
		// 		udpSocket.receive(packet);
		// 	} catch (Exception e) {
		// 		if (!terminateThread.get()) {
		// 			logger.error("Error receiving data from UDP socket.",e);
		// 		}
		// 		logger.debug("Interrupted");
		// 		continue;
		// 	}
		// 	String receivedString = new String(packet.getData());
		// 	logger.debug("Packet of size {} received from {}:{}: [{}]",
		// 			receivedString.length(),
		// 			packet.getAddress(),
		// 			packet.getPort(),
		// 			receivedString);
		// 	// Put the string in the buffer
		// 	if (receivedString.isEmpty()) {
		// 		logger.warn("Got an empty string from the socket?!?");
		// 		continue;
		// 	}
		// 	boolean addedToQueue = receivedStringQueue.offer(receivedString);
		// 	if (!addedToQueue) {
		// 		logger.warn("Queue full: string rejected: [{}]",receivedString);
		// 	} else {
		// 		logger.debug("String [{}] pushed in buffer (size of buffer ={})",
		// 				receivedString,
		// 				receivedStringQueue.size());
		// 	}
		// }
		// done.countDown();
		// logger.debug("UDP loop thread terminated");
	}

	/**
	 * Submit a value received from the socket to the
	 * plugin
	 *
	 * @param str
	 */
	private void submitValue(String str) {
		if (str==null || str.isEmpty()) {
			throw new IllegalArgumentException("Invalid value from socket");
		}
		logger.debug("Submitting [{}] to the plugin library",str);

		MessageDao message;
		try {
			message = MAPPER.readValue(str, MessageDao.class);
		} catch (Exception e) {
			logger.error("Exception parsing JSON string [{}]: value lost",str,e);
			return;
		}
		Object value;
		try {
			value = convertStringToObject(message.getValue(),message.getValueType());
		} catch (PluginException pe) {
			logger.error("Exception building the object of type [{}] and value[{}]: value lost",
					message.getValue(),
					message.getValueType(),
					pe);
			return;
		}

		if (!Objects.isNull(message.getOperMode()) && !message.getOperMode().isEmpty()) {
			try {
				OperationalMode mode = OperationalMode.valueOf(message.getOperMode());
				plugin.setOperationalMode(message.getMonitorPointId(), mode);
			} catch (PluginException e) {
				// This exception is thrown by plugin.setOperationalMode
				logger.error("Error setting the operational mode {} for  monitor point {}",
						message.getOperMode(),
						message.getMonitorPointId());
			}catch (Exception e) {
				logger.error("Error decoding operational mode {} for  monitor point {}",
						message.getOperMode(),
						message.getMonitorPointId());
			}

		}

		long timestamp;
		try {
			timestamp = ISO8601Helper.timestampToMillis(message.getTimestamp());
		} catch (Exception e) {
			logger.error("Exception parsing te timestamp [{}]: using actual time",message.getTimestamp(),e);
			timestamp=System.currentTimeMillis();
		}

		Sample sample = new Sample(value,timestamp);
		try {
			plugin.updateMonitorPointValue(message.getMonitorPointId(), sample);
		} catch (Exception e) {
			logger.error("Exception adding the sample [{}] to the plugin: value lost",message.getMonitorPointId(),e);
		}
	}

	/**
	 * Parse the passed string of the give type into a java object
	 *
	 * @param value the string representation of the value
	 * @param valueType the type of the value
	 * @return the java object for the give value and type
	 * @throws PluginException in case of error building the object
	 */
	private Object convertStringToObject(String value, String valueType) throws PluginException {
		if (value==null || value.isEmpty()) {
			throw new PluginException("Invalid value string to parse");
		}
		if (valueType==null || valueType.isEmpty()) {
			throw new PluginException("Invalid value type");
		}

		IASTypes iasType;
		try {
			iasType = IASTypes.valueOf(valueType);
		} catch (Exception e) {
			throw new PluginException("Unrecognized/Unsupported value type "+valueType);
		}
		try {
			return iasType.convertStringToObject(value);
		} catch (Exception e) {
			throw new PluginException("Exception converting "+value+" to an object of type "+iasType,e);
		}

	}

	/**
	 * Starts the VisualInspectionPlugin
	 *
	 * @return the latch signaling the termination of the thread
	 * @throws PluginException in case of error running the plugin
	 */
	public CountDownLatch setUp() throws PluginException {
		logger.debug("Instantiating the UDP socket with port {}",udpPort);
		try {
			udpSocket = new DatagramSocket(udpPort);
		} catch (SocketException se) {
			throw new PluginException("Error building the UDP socket",se);
		}
		logger.debug("Starting the plugin");
		try {
			plugin.start();
		} catch (PublisherException pe) {
			throw new PluginException("Error starting the plugin",pe);
		}
		logger.debug("Starting the string processor loop");
		stringProcessorThread = Plugin.getThreadFactory().newThread(new Runnable() {
			public void run() {
				logger.debug("String processor thread started");
				while (!terminateThread.get()) {
					String strToInject=null;
					try {
						strToInject= receivedStringQueue.take();
					} catch (InterruptedException ie) {
						if (!terminateThread.get()) {
							logger.warn("Interrupted",ie);
						}
						continue;
					}
					try {
						if (strToInject!=null && !strToInject.isEmpty()) {
							submitValue(strToInject);
						}
					} catch (Exception e) {
						logger.warn("Error processing [{}]: ignored",strToInject,e);
					}
				}
				logger.debug("String processor thread exited");
			}
		});
		stringProcessorThread.start();

		logger.debug("Starting the UDP loop");
		udpRecvThread = Plugin.getThreadFactory().newThread(this);
		udpRecvThread.start();
		// Adds the shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				shutdown();
			}
		}, "VisualInspectionPlugin shutdown hook"));
		logger.info("Started.");
		return done;
	}

	/**
	 * Shuts down the the thread and closes the plugin
	 */
	public void shutdown() {
		boolean alreadyShutDown=terminateThread.getAndSet(true);
		if (alreadyShutDown) {
			logger.warn("Already shut down!");
			return;
		}
		logger.debug("Closing the UDP socket");
		udpSocket.close();
		logger.debug("Shutting down the UDP loop");
		if (udpRecvThread!=null) {
			udpRecvThread.interrupt();
			logger.debug("UDP loop interrupted");
		}
		if (stringProcessorThread!=null) {
			stringProcessorThread.interrupt();
			logger.debug("String processor thread interrupted");
		}
		boolean terminatedInTime;
		try {
			logger.debug("Waiting for the UDP loop thread to terminate");
			terminatedInTime = done.await(2, TimeUnit.SECONDS);
			if (!terminatedInTime) {
				logger.warn("The UDP loop did not terminate in time");
			}
		} catch (InterruptedException e) {
			logger.warn("Interrupetd while waiting for thread termination",e);
		}
		logger.debug("Shutting down the plugin");
		plugin.shutdown();
		logger.info("Cleaned up.");
	}

}
