package mapeditor.jagex.rt3;

import java.io.*;
import java.net.Socket;
import java.util.zip.*;

import mapeditor.Main;
import mapeditor.jagex.rt3.util.collection.*;

public class OnDemandFetcher extends OnDemandFetcherParent implements Runnable {

	private Main editorInstance;
	private boolean DISABLE_CRC = true;

	private boolean crcMatches(int i, int j, byte abyte0[]) {
		if (abyte0 == null || abyte0.length < 2)
			return false;
		if (clientInstance == null || DISABLE_CRC)
			return true;
		int k = abyte0.length - 2;
		int footer = ((abyte0[k] & 0xff) << 8) + (abyte0[k + 1] & 0xff);
		crc32.reset();
		crc32.update(abyte0, 0, k);
		int i1 = (int) crc32.getValue();
		return footer == i && i1 == j;
	}

	private void readData() {
		try {
			int j = inputStream.available();
			if (expectedSize == 0 && j >= 10) {
				waiting = true;
				for (int k = 0; k < 10; k += inputStream.read(ioBuffer, k, 6 - k))
					;
				int dataType = ioBuffer[0] & 0xff;
				int dataIndex = ((ioBuffer[1] & 0xff) << 16) + ((ioBuffer[2] & 0xff) << 8) + (ioBuffer[3] & 0xff);
				int fileLength = ((ioBuffer[4] & 0xff) << 32) + ((ioBuffer[5] & 0xff) << 16) + ((ioBuffer[6] & 0xff) << 8) + (ioBuffer[7] & 0xff);
				int chunkID = ((ioBuffer[8] & 0xff) << 8) + (ioBuffer[9] & 0xff);

				current = null;
				for (OnDemandData onDemandData = (OnDemandData) requested
						.getFront(); onDemandData != null; onDemandData = (OnDemandData) requested.getNext()) {
					if (onDemandData.dataType == dataType && onDemandData.ID == dataIndex)
						current = onDemandData;
					if (current != null)
						onDemandData.loopCycle = 0;
				}

				if (current != null) {
					loopCycle = 0;
					if (fileLength == 0) {
						Signlink.reporterror("Rej: " + dataType + "," + dataIndex);
						current.buffer = null;
						if (current.incomplete)
							synchronized (zippedNodes) {
								zippedNodes.insertBack(current);
							}
						else
							current.unlink();
						current = null;
					} else {
						if (current.buffer == null && chunkID == 0)
							current.buffer = new byte[fileLength];
						if (current.buffer == null && chunkID != 0)
							throw new IOException("missing start of file");
					}
				}
				completedSize = chunkID * 500;
				expectedSize = 500;
				if (expectedSize > fileLength - chunkID * 500)
					expectedSize = fileLength - chunkID * 500;
			}
			if (expectedSize > 0 && j >= expectedSize) {
				waiting = true;
				byte abyte0[] = ioBuffer;
				int i1 = 0;
				if (current != null) {
					abyte0 = current.buffer;
					i1 = completedSize;
				}
				for (int k1 = 0; k1 < expectedSize; k1 += inputStream.read(abyte0, k1 + i1, expectedSize - k1))
					;
				if (expectedSize + completedSize >= abyte0.length && current != null) {
					if (clientInstance.jagexFileStores[0] != null)
						clientInstance.jagexFileStores[current.dataType + 1].put(abyte0.length, abyte0, current.ID);
					if (!current.incomplete && current.dataType == 3) {
						current.incomplete = true;
						current.dataType = 93;
					}
					if (current.incomplete)
						synchronized (zippedNodes) {
							zippedNodes.insertBack(current);
						}
					else
						current.unlink();
				}
				expectedSize = 0;
			}
		} catch (IOException ioexception) {
			try {
				socket.close();
			} catch (Exception _ex) {
			}
			socket = null;
			inputStream = null;
			outputStream = null;
			expectedSize = 0;
		}
	}

	public int getLandscapeID(int area) {
		int index = 0;

		for (int i = 0; i < regionHash.length; i++) {
			if (regionHash[i] == area) {
				index = i;
				break;
			}
		}

		return regionLandscapeIndex[index];
	}

	public int getObjectscapeID(int area) {
		int index = 0;

		for (int i = 0; i < regionHash.length; i++) {
			if (regionHash[i] == area) {
				index = i;
				break;
			}
		}

		return regionObjectMapIndex[index];
	}

	public void start(JagexArchive jagexArchive, Client client1) {
		String versionFileNames[] = { "model_version", "anim_version", "midi_version", "map_version" };
		for (int cachePtr = 0; cachePtr < 4; cachePtr++) {
			byte data[] = jagexArchive.getDataForName(versionFileNames[cachePtr]);
			int j = data.length / 2;
			Packet stream = new Packet(data);
			versions[cachePtr] = new int[j];
			fileStatus[cachePtr] = new byte[j];
			for (int l = 0; l < j; l++)
				versions[cachePtr][l] = stream.g2();

		}

		String crcFileNames[] = { "model_crc", "anim_crc", "midi_crc", "map_crc" };
		for (int cachePtr = 0; cachePtr < 4; cachePtr++) {
			byte data[] = jagexArchive.getDataForName(crcFileNames[cachePtr]);
			int size = data.length / 4;
			Packet stream_1 = new Packet(data);
			crcs[cachePtr] = new int[size];
			for (int l1 = 0; l1 < size; l1++)
				crcs[cachePtr][l1] = stream_1.g4();

		}

		byte model_indexes[] = jagexArchive.getDataForName("model_index");
		int count = versions[0].length;
		modelIndices = new byte[count];
		for (int dataPtr = 0; dataPtr < count; dataPtr++)
			if (dataPtr < model_indexes.length)
				modelIndices[dataPtr] = model_indexes[dataPtr];
			else
				modelIndices[dataPtr] = 0;

		boolean readFromCache = false;

		if (readFromCache) {
			model_indexes = jagexArchive.getDataForName("map_index");

		} else {
			model_indexes = Client.ReadFile("map_index.dat");
		}
		Packet indexStream = new Packet(model_indexes);
		count = model_indexes.length / 6;
		regionHash = new int[count];
		regionLandscapeIndex = new int[count];
		regionObjectMapIndex = new int[count];
		readPreload = new int[count];
		for (int i2 = 0; i2 < count; i2++) {
			regionHash[i2] = indexStream.g2();
			regionLandscapeIndex[i2] = indexStream.g2();
			regionObjectMapIndex[i2] = indexStream.g2();
			// readPreload[i2] = indexStream.g1();
		}

		model_indexes = jagexArchive.getDataForName("anim_index");
		indexStream = new Packet(model_indexes);
		count = model_indexes.length / 2;
		animIndices = new int[count];
		for (int j2 = 0; j2 < count; j2++)
			animIndices[j2] = indexStream.g2();

		model_indexes = jagexArchive.getDataForName("midi_index");
		indexStream = new Packet(model_indexes);
		count = model_indexes.length;
		midiIndices = new int[count];
		for (int k2 = 0; k2 < count; k2++)
			midiIndices[k2] = indexStream.g1();

		clientInstance = client1;
		running = true;
		clientInstance.startRunnable(this, 2);
	}

	public void start(JagexArchive jagexArchive, Main client1) {
		String versionFileNames[] = { "model_version", "anim_version", "midi_version", "map_version" };
		for (int cachePtr = 0; cachePtr < 4; cachePtr++) {
			byte data[] = jagexArchive.getDataForName(versionFileNames[cachePtr]);
			int j = data.length / 2;
			Packet stream = new Packet(data);
			versions[cachePtr] = new int[j];
			fileStatus[cachePtr] = new byte[j];
			for (int l = 0; l < j; l++)
				versions[cachePtr][l] = stream.g2();

		}

		String crcFileNames[] = { "model_crc", "anim_crc", "midi_crc", "map_crc" };
		for (int cachePtr = 0; cachePtr < 4; cachePtr++) {
			byte data[] = jagexArchive.getDataForName(crcFileNames[cachePtr]);
			int size = data.length / 4;
			Packet stream_1 = new Packet(data);
			crcs[cachePtr] = new int[size];
			for (int l1 = 0; l1 < size; l1++)
				crcs[cachePtr][l1] = stream_1.g4();

		}

		byte model_indexes[] = jagexArchive.getDataForName("model_index");
		int modelCount = versions[0].length;
		modelIndices = new byte[modelCount];
		for (int dataPtr = 0; dataPtr < modelCount; dataPtr++)
			if (dataPtr < model_indexes.length)
				modelIndices[dataPtr] = model_indexes[dataPtr];
			else
				modelIndices[dataPtr] = 0;

		boolean readFromCache = true;

		if (readFromCache) {
			model_indexes = jagexArchive.getDataForName("map_index");

		} else {
			model_indexes = Client.ReadFile("map_index.dat");
		}
		Packet indexStream = new Packet(model_indexes);
		modelCount = model_indexes.length / 6;
		regionHash = new int[modelCount];
		regionLandscapeIndex = new int[modelCount];
		regionObjectMapIndex = new int[modelCount];
		readPreload = new int[modelCount];
		for (int i2 = 0; i2 < modelCount; i2++) {
			regionHash[i2] = indexStream.g2();
			regionLandscapeIndex[i2] = indexStream.g2();
			regionObjectMapIndex[i2] = indexStream.g2();
			// readPreload[i2] = indexStream.g1();
		}

		model_indexes = jagexArchive.getDataForName("anim_index");
		indexStream = new Packet(model_indexes);
		modelCount = model_indexes.length / 2;
		animIndices = new int[modelCount];
		for (int j2 = 0; j2 < modelCount; j2++)
			animIndices[j2] = indexStream.g2();

		model_indexes = jagexArchive.getDataForName("midi_index");
		indexStream = new Packet(model_indexes);
		modelCount = model_indexes.length;
		midiIndices = new int[modelCount];
		for (int k2 = 0; k2 < modelCount; k2++)
			midiIndices[k2] = indexStream.g1();

		editorInstance = client1;
		running = true;
		editorInstance.startRunnable(this, 2);
	}

	public int getNodeCount() {
		synchronized (queue) {
			return queue.getSize();
		}
	}

	public void disable() {
		running = false;
	}

	public void method554(boolean flag) {
		int j = regionHash.length;
		for (int k = 0; k < j; k++)
			if (flag || readPreload[k] != 0) {
				method563((byte) 2, 3, regionObjectMapIndex[k]);
				method563((byte) 2, 3, regionLandscapeIndex[k]);
			}

	}

	public int getVersionCount(int j) {
		return versions[j].length;
	}

	private void closeRequest(OnDemandData onDemandData) {
		System.out.println("close request");
		if (clientInstance == null)
			return;
		try {
			if (socket == null) {
				long l = System.currentTimeMillis();
				if (l - openSocketTime < 4000L)
					return;
				openSocketTime = l;
				socket = clientInstance.openSocket(43594 + Client.portOff);
				inputStream = socket.getInputStream();
				outputStream = socket.getOutputStream();
				outputStream.write(15);
				for (int j = 0; j < 8; j++)
					inputStream.read();

				loopCycle = 0;
			}
			ioBuffer[0] = (byte) onDemandData.dataType;
			ioBuffer[1] = (byte) (onDemandData.ID >> 8);
			ioBuffer[2] = (byte) onDemandData.ID;
			if (onDemandData.incomplete)
				ioBuffer[3] = 2;
			else if (!clientInstance.loggedIn)
				ioBuffer[3] = 1;
			else
				ioBuffer[3] = 0;
			outputStream.write(ioBuffer, 0, 4);
			writeLoopCycle = 0;
			anInt1349 = -10000;
			return;
		} catch (IOException ioexception) {
		}
		try {
			socket.close();
		} catch (Exception _ex) {
		}
		socket = null;
		inputStream = null;
		outputStream = null;
		expectedSize = 0;
		anInt1349++;
	}

	public int getAnimCount() {
		return animIndices.length;
	}

	public int getModelCount() {
		return 65535;
	}

	public void loadToCache(int dataType, int id) {
		/*
		 * if(dataType < 0 || dataType > versions.length || id < 0 || id >
		 * versions[dataType].length) return; if(versions[dataType][id] == 0) return;
		 */
		synchronized (queue) {
			for (OnDemandData onDemandData = (OnDemandData) queue
					.getFront(); onDemandData != null; onDemandData = (OnDemandData) queue.getNext())
				if (onDemandData.dataType == dataType && onDemandData.ID == id)
					return;

			OnDemandData onDemandData_1 = new OnDemandData();
			onDemandData_1.dataType = dataType;
			onDemandData_1.ID = id;
			onDemandData_1.incomplete = true;
			synchronized (aClass19_1370) {
				aClass19_1370.insertBack(onDemandData_1);
			}
			queue.insertBack(onDemandData_1);
		}
	}

	public int getModelIndex(int i) {
		return modelIndices[i] & 0xff;
	}

	public void run() {
		try {
			while (running) {
				onDemandCycle++;
				int i = 20;
				if (clientInstance != null) {
					if (anInt1332 == 0 && clientInstance.jagexFileStores[0] != null)
						i = 50;
				} else {
					if (anInt1332 == 0 && editorInstance.jagexFileStores[0] != null)
						i = 50;
				}
				try {
					Thread.sleep(i);
				} catch (Exception _ex) {
					;
				}
				waiting = true;
				for (int j = 0; j < 100; j++) {
					if (!waiting)
						break;
					waiting = false;
					checkReceived();
					if (uncompletedCount == 0 && j >= 5)
						break;
					method568();
					if (inputStream != null)
						readData();
				}

				boolean flag = false;
				for (OnDemandData onDemandData = (OnDemandData) requested
						.getFront(); onDemandData != null; onDemandData = (OnDemandData) requested.getNext())
					if (onDemandData.incomplete) {
						flag = true;
						onDemandData.loopCycle++;
						if (onDemandData.loopCycle > 50) {
							onDemandData.loopCycle = 0;
							closeRequest(onDemandData);
						}
					}

				if (!flag) {
					for (OnDemandData onDemandData_1 = (OnDemandData) requested
							.getFront(); onDemandData_1 != null; onDemandData_1 = (OnDemandData) requested.getNext()) {
						flag = true;
						onDemandData_1.loopCycle++;
						if (onDemandData_1.loopCycle > 50) {
							onDemandData_1.loopCycle = 0;
							closeRequest(onDemandData_1);
						}
					}

				}
				if (flag) {
					loopCycle++;
					if (loopCycle > 750) {
						try {
							socket.close();
						} catch (Exception _ex) {
						}
						socket = null;
						inputStream = null;
						outputStream = null;
						expectedSize = 0;
					}
				} else {
					loopCycle = 0;
					statusString = "";
				}
				if ((clientInstance != null && clientInstance.loggedIn) && socket != null && outputStream != null
						&& (anInt1332 > 0 || clientInstance == null || clientInstance.jagexFileStores[0] == null)) {
					writeLoopCycle++;
					if (writeLoopCycle > 500) {
						writeLoopCycle = 0;
						ioBuffer[0] = 0;
						ioBuffer[1] = 0;
						ioBuffer[2] = 0;
						ioBuffer[3] = 10;
						try {
							outputStream.write(ioBuffer, 0, 4);
						} catch (IOException _ex) {
							loopCycle = 5000;
						}
					}
				}
			}
		} catch (Exception exception) {
			Signlink.reporterror("od_ex " + exception.getMessage());
		}
	}

	public void method560(int i, int j) {
		if (clientInstance != null) {
			if (clientInstance.jagexFileStores[0] == null)
				return;
		} else {
			if (editorInstance.jagexFileStores[0] == null)
				return;
		}
		if (fileStatus[j][i] == 0)
			return;
		if (anInt1332 == 0)
			return;
		OnDemandData onDemandData = new OnDemandData();
		onDemandData.dataType = j;
		onDemandData.ID = i;
		onDemandData.incomplete = false;
		synchronized (aClass19_1344) {
			aClass19_1344.insertBack(onDemandData);
		}
	}

	public OnDemandData getNextNode() {
		OnDemandData onDemandData;
		synchronized (zippedNodes) {
			onDemandData = (OnDemandData) zippedNodes.popFront();
		}
		if (onDemandData == null)
			return null;
		synchronized (queue) {
			onDemandData.unlinkQueue();
		}
		if (onDemandData.buffer == null)
			return onDemandData;
		int i = 0;
		try {
			GZIPInputStream gzipinputstream = new GZIPInputStream(new ByteArrayInputStream(onDemandData.buffer));
			do {
				int k = gzipinputstream.read(gzipInputBuffer, i, gzipInputBuffer.length - i);
				if (k == -1)
					break;
				i += k;
			} while (true);
		} catch (IOException _ex) {
			throw new RuntimeException("error unzipping");
		}
		onDemandData.buffer = new byte[i];
		System.arraycopy(gzipInputBuffer, 0, onDemandData.buffer, 0, i);

		return onDemandData;
	}

	public int getMapIndex(int i, int map_z, int map_x) {
		int i1 = (map_x << 8) + map_z;
		for (int j1 = 0; j1 < regionHash.length; j1++)
			if (regionHash[j1] == i1)
				if (i == 0)
					return regionLandscapeIndex[j1];
				else
					return regionObjectMapIndex[j1];
		return -1;
	}

	public void dumpmaps() {
		boolean doit = false;
		if (doit)
			for (int ptr = 0; ptr < regionHash.length; ptr++) {
				int x = regionHash[ptr] >> 8;
				int y = regionHash[ptr] & 0xFF;
				FileOperations.WriteFile("./mapdump/" + x + "_" + y + ".dat", GZIPWrapper
						.decompress(clientInstance.jagexFileStores[4].decompress(regionLandscapeIndex[ptr])));
				FileOperations.WriteFile("./mapdump/" + x + "_" + y + ".dat", GZIPWrapper
						.decompress(clientInstance.jagexFileStores[4].decompress(regionObjectMapIndex[ptr])));
			}
	}

	public void requestData(int id) {
		loadToCache(0, id);
	}

	public void method563(byte byte0, int i, int j) {
		if (clientInstance != null) {
			if (clientInstance.jagexFileStores[0] == null)
				return;
		} else {
			if (editorInstance.jagexFileStores[0] == null)
				return;
		}

//		if (versions[i][j] == 0)
//			return;
//		byte abyte0[];
//		if (clientInstance != null)
//			abyte0 = clientInstance.jagexFileStores[i + 1].decompress(j);
//		else
//			abyte0 = editorInstance.jagexFileStores[i + 1].decompress(j);
//		if (crcMatches(versions[i][j], crcs[i][j], abyte0))
//			return;
//		fileStatus[i][j] = byte0;

		if (byte0 > anInt1332)
			anInt1332 = byte0;
		totalFiles++;
	}

	public boolean method564(int i) {
		for (int k = 0; k < regionHash.length; k++)
			if (regionObjectMapIndex[k] == i)
				return true;
		return false;
	}

	private void handleFailed() {
		uncompletedCount = 0;
		completedCount = 0;
		for (OnDemandData onDemandData = (OnDemandData) requested
				.getFront(); onDemandData != null; onDemandData = (OnDemandData) requested.getNext())
			if (onDemandData.incomplete)
				uncompletedCount++;
			else
				completedCount++;

		while (uncompletedCount < 10) {
			OnDemandData onDemandData_1 = (OnDemandData) aClass19_1368.popFront();
			if (onDemandData_1 == null)
				break;
			if (fileStatus[onDemandData_1.dataType][onDemandData_1.ID] != 0)
				filesLoaded++;
			fileStatus[onDemandData_1.dataType][onDemandData_1.ID] = 0;
			requested.insertBack(onDemandData_1);
			uncompletedCount++;
			closeRequest(onDemandData_1);
			waiting = true;
		}
	}

	public void method566() {
		synchronized (aClass19_1344) {
			aClass19_1344.clear();
		}
	}

	private void checkReceived() {
		OnDemandData onDemandData;
		synchronized (aClass19_1370) {
			onDemandData = (OnDemandData) aClass19_1370.popFront();
		}
		while (onDemandData != null) {
			waiting = true;
			byte abyte0[] = null;
			if (clientInstance != null) {
				if (clientInstance.jagexFileStores[0] != null)
					abyte0 = clientInstance.jagexFileStores[onDemandData.dataType + 1].decompress(onDemandData.ID);
			} else {
				if (editorInstance.jagexFileStores[0] != null)
					abyte0 = editorInstance.jagexFileStores[onDemandData.dataType + 1].decompress(onDemandData.ID);
			}
			if (!crcMatches(versions[onDemandData.dataType][onDemandData.ID],
					crcs[onDemandData.dataType][onDemandData.ID], abyte0))
				abyte0 = null;
			synchronized (aClass19_1370) {
				if (abyte0 == null) {
					aClass19_1368.insertBack(onDemandData);
				} else {
					onDemandData.buffer = abyte0;
					synchronized (zippedNodes) {
						zippedNodes.insertBack(onDemandData);
					}
				}
				onDemandData = (OnDemandData) aClass19_1370.popFront();
			}
		}
	}

	private void method568()// used in client startup
	{
		while (uncompletedCount == 0 && completedCount < 10) {
			if (anInt1332 == 0)
				break;
			OnDemandData onDemandData;
			synchronized (aClass19_1344) {
				onDemandData = (OnDemandData) aClass19_1344.popFront();
			}
			while (onDemandData != null) {
				if (fileStatus[onDemandData.dataType][onDemandData.ID] != 0) {
					fileStatus[onDemandData.dataType][onDemandData.ID] = 0;
					requested.insertBack(onDemandData);
					closeRequest(onDemandData);
					waiting = true;
					if (filesLoaded < totalFiles)
						filesLoaded++;
					statusString = "Loading extra files - " + (filesLoaded * 100) / totalFiles + "%";
					completedCount++;
					if (completedCount == 10)
						return;
				}
				synchronized (aClass19_1344) {
					onDemandData = (OnDemandData) aClass19_1344.popFront();
				}
			}
			for (int j = 0; j < 4; j++) {
				byte abyte0[] = fileStatus[j];
				int k = abyte0.length;
				for (int l = 0; l < k; l++)
					if (abyte0[l] == anInt1332) {
						abyte0[l] = 0;
						OnDemandData onDemandData_1 = new OnDemandData();
						onDemandData_1.dataType = j;
						onDemandData_1.ID = l;
						onDemandData_1.incomplete = false;
						requested.insertBack(onDemandData_1);
						closeRequest(onDemandData_1);
						waiting = true;
						if (filesLoaded < totalFiles)
							filesLoaded++;
						statusString = "Loading extra files - " + (filesLoaded * 100) / totalFiles + "%";
						completedCount++;
						if (completedCount == 10)
							return;
					}
			}
			anInt1332--;
		}
	}

	public boolean method569(int i) {
		return midiIndices[i] == 1;
	}

	public OnDemandFetcher() {
		requested = new Deque();
		statusString = "";
		crc32 = new CRC32();
		ioBuffer = new byte[500];
		fileStatus = new byte[4][];
		aClass19_1344 = new Deque();
		running = true;
		waiting = false;
		zippedNodes = new Deque();
		gzipInputBuffer = new byte[65000];
		queue = new Queue();
		versions = new int[4][];
		crcs = new int[4][];
		aClass19_1368 = new Deque();
		aClass19_1370 = new Deque();
	}

	private int totalFiles;
	private final Deque requested;
	private int anInt1332;
	public String statusString;
	private int writeLoopCycle;
	private long openSocketTime;
	private int[] regionObjectMapIndex;
	private final CRC32 crc32;
	private final byte[] ioBuffer;
	public int onDemandCycle;
	private final byte[][] fileStatus;
	public Client clientInstance;
	private final Deque aClass19_1344;
	private int completedSize;
	private int expectedSize;
	private int[] midiIndices;
	public int anInt1349;
	private int[] regionLandscapeIndex;
	private int filesLoaded;
	private boolean running;
	private OutputStream outputStream;
	private int[] readPreload;
	private boolean waiting;
	private final Deque zippedNodes;
	private final byte[] gzipInputBuffer;
	private int[] animIndices;
	private final Queue queue;
	private InputStream inputStream;
	private Socket socket;
	private final int[][] versions;
	private final int[][] crcs;
	private int uncompletedCount;
	private int completedCount;
	private final Deque aClass19_1368;
	private OnDemandData current;
	private final Deque aClass19_1370;
	private int[] regionHash;
	private byte[] modelIndices;
	private int loopCycle;

	public byte[] getDataFromCache(int id, int c) {
		if (clientInstance != null)
			return GZIPWrapper.decompress(clientInstance.jagexFileStores[c + 1].decompress(id));
		else
			return GZIPWrapper.decompress(editorInstance.jagexFileStores[c + 1].decompress(id));
	}
}
