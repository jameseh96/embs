package transport;

import com.ibm.saguaro.logger.Logger;
import com.ibm.saguaro.system.LED;
import com.ibm.saguaro.system.Mote;
import com.ibm.saguaro.system.Radio;
import com.ibm.saguaro.system.Time;
import com.ibm.saguaro.system.Timer;
import com.ibm.saguaro.system.TimerEvent;
import com.ibm.saguaro.system.Util;
import com.ibm.saguaro.system.csr;

public class Routing {
	public static final byte PING = 0x00;
	public static final byte PONG = 0x01;
	public static final byte ROUTING_REQ = 0x02;
	public static final byte ROUTING = 0x03;
	public static final byte ROUTABLE_MESSAGE = 0x04;
	
	private static Timer pingTimer;
	private static Timer testTimer;
	private static Timer flashTimer;
	private static long pingTimerDelay;
	private static long testTimerDelay;
	private static long flashTimerDelay;
	static int shortAddr;
	static int panId;
	
	private static Route[] routingTable = new Route[20];
	private static int knownRoutes = 0;
	
	private static byte[] flashPattern = new byte[5];
	private static int flashIndex = 0;

	static {
		shortAddr = TXRX.getShortAddr();
		panId = TXRX.getPanId();

		pingTimer = new Timer();
		pingTimer.setCallback(new TimerEvent(null) {
			public void invoke(byte param, long time) {
				Routing.ping(param, time);
			}
		});
		
		pingTimerDelay = Time.toTickSpan(Time.MILLISECS, 2000);
		pingTimer.setAlarmBySpan(pingTimerDelay);

		testTimer = new Timer();
		testTimer.setCallback(new TimerEvent(null) {
			public void invoke(byte param, long time) {
				Routing.transmit(param, time);
			}
		});

		testTimerDelay = Time.toTickSpan(Time.MILLISECS, 4000);
		testTimer.setAlarmBySpan(testTimerDelay);

		TXRX.addOnReceiveCallback(new TXRX.OnReceiveCallback() {
			@Override
			public void invoke(DataFrame df) {
				Routing.onReceiveFrame(df);
			}
		});
		
		flashTimer = new Timer();
		flashTimer.setCallback(new TimerEvent(null) {
			public void invoke(byte param, long time) {
				Routing.flashLEDS(param, time);
			}
		});

		flashTimerDelay = Time.toTickSpan(Time.MILLISECS, 1000);

		TXRX.addOnReceiveCallback(new TXRX.OnReceiveCallback() {
			@Override
			public void invoke(DataFrame df) {
				Routing.onReceiveFrame(df);
			}
		});
	}
	
	public static void flashLEDS(byte param, long time) {
		for (int i = 0; i < 3; i++) {
			LED.setState((byte) i, (byte)0);
		}
		LED.setState((byte) flashIndex++, (byte)1);
		if (flashIndex < 5) {
			flashTimer.setAlarmBySpan(flashTimerDelay);
		}
	}

	public static void ping(byte param, long time) {
		DataFrame df = new DataFrame();
		df.setSrcAddr(shortAddr);
		df.setPanId(panId);
		df.setDestAddr(Radio.SADDR_BROADCAST);
		df.setPayload(new byte[] {PING});
		TXRX.sendDataFrame(df);
//		pingTimer.setAlarmBySpan(pingTimerDelay);
	}
	
	public static void transmit(byte param, long time) {
		DataFrame data = new DataFrame();
		data.setSrcAddr(shortAddr);
		data.setDestAddr(0x00);
		byte[] payload = new byte[8];
		payload[0] = ROUTABLE_MESSAGE;
		Util.set16le(payload, 1, 0x5400);
		payload[3] = 1;
		payload[4] = 2;
		payload[5] = 0;
		payload[6] = 2;
		payload[7] = 1;
		data.setPayload(payload);
		
		Routing.routeMessage(data);
		Logger.appendString(csr.s2b("Transmitting"));
		Logger.flush(Mote.INFO);
	}

	private static void onReceiveFrame(DataFrame df) {
		if (!df.isData()) {
			return;
		}
		byte[] payload = df.getPayload();

		switch (payload[0]) {
		case PING:
			onPing(df.getSrcAddr());
			// Fallthrough
		case PONG:
			onNeighbourContact(df.getSrcAddr());
			break;
		case ROUTING:
			mergeNeighbourRoutingTable(df);
			break;
		case ROUTABLE_MESSAGE:
			routeMessage(df);
		default:
			return;
		}
	}
	
	private static void routeMessage(DataFrame df) {
		byte[] payload = df.getPayload();
		int dest = Util.get16le(payload, 1);
		Logger.appendHexInt(dest);
		Logger.flush(Mote.INFO);
		if (dest == shortAddr) {
			onMessage(df);
		} else {
			for (int i = 0; i < knownRoutes; i++) {
				Route route = routingTable[i];
				if (route.addr == dest) {
					DataFrame df2 = new DataFrame(df);
					df2.setPanId(0x01);
					df2.setSrcAddr(shortAddr);
					df2.setDestAddr(route.peerAddr == 0xFFFF ? dest : route.peerAddr);
					Logger.appendHexInt(df2.getPanId());
					Logger.appendHexInt(df2.getSrcAddr());
					Logger.appendHexInt(df2.getDestAddr());
					Logger.flush(Mote.INFO);
					TXRX.sendDataFrame(df2);
					break;
				}
			}
		}
	}
	
	private static void onMessage(DataFrame df) {
		byte[] payload = df.getPayload();
		
		for (int i = 1; i < 6; i++) {
			flashPattern[i-1] = payload[i];
		}
		
		flashTimer.setAlarmBySpan(flashTimerDelay);
	}

	private static void onPing(int addr) {
		DataFrame df = new DataFrame();
		df.setSrcAddr(shortAddr);
		df.setPanId(panId);
		df.setDestAddr(addr);
		df.setPayload(new byte[] {PONG});
		TXRX.sendDataFrame(df);
	}

	private static void onNeighbourContact(int addr) {
		boolean updated = updateRoutingTable(addr, Radio.SADDR_BROADCAST, 0);
		if (updated) {
			broadcastRoutingTable();
			logRoutingTable();
		}
	}
	
	private static void mergeNeighbourRoutingTable(DataFrame df) {
		boolean shouldPropagate = false;
		byte[] payload = df.getPayload();
		for (int i = 0; ((i*3)+4) < payload.length; i++) {
			int addr = Util.get16le(payload, (i*3)+1);
			int hops = payload[(i*3)+3];
			shouldPropagate = shouldPropagate || updateRoutingTable(addr, df.getSrcAddr(), hops + 1);
		}
		
		if (shouldPropagate) {
			logRoutingTable();
			broadcastRoutingTable();
		}
	}
	
	private static boolean updateRoutingTable(int addr, int peerAddr, int hops) {
		for (int i = 0; i < knownRoutes; i++) {
			Route route = routingTable[i];
			if (route.addr == addr) {
				if (route.peerAddr == Radio.SADDR_BROADCAST) {
					return false;
				}
				if (route.hops > hops) {
					route.peerAddr = peerAddr;
					route.hops = hops;
					return true;
				}
				else if (route.hops == hops) {
					return false;
				}
			}
		}
		// Not an existing route
		routingTable[knownRoutes++] = new Route(addr, peerAddr, hops);
		return true;
	}
	
	private static void broadcastRoutingTable() {
		DataFrame df = new DataFrame();
		df.setPanId(panId);
		df.setSrcAddr(shortAddr);
		df.setDestAddr(Radio.SADDR_BROADCAST);

		byte[] payload = new byte[(3 * knownRoutes)+1]; // addr addr hops
		
		payload[0] = ROUTING;
		
		for (int i = 0; i < knownRoutes; i++) {
			Util.set16le(payload, (i*3)+1, routingTable[i].addr);
			payload[(i*3)+3] = (byte)routingTable[i].hops;
		}

		df.setPayload(payload);
		TXRX.sendDataFrame(df);
	}
	
	private static void logRoutingTable() {
		for (int i = 0; i < knownRoutes; i++) {
			Logger.appendHexInt(routingTable[i].addr);
			Logger.appendHexInt(routingTable[i].peerAddr);
			Logger.appendHexInt(routingTable[i].hops);
		}
		
		Logger.flush(Mote.INFO);
	}

	public static class Route {
		int addr;
		int peerAddr;
		int hops;
		public Route(int addr, int peerAddr, int hops) {
			this.addr = addr;
			this.peerAddr = peerAddr;
			this.hops = hops;
		}
	}
}
