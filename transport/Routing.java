package transport;

import com.ibm.saguaro.logger.Logger;
import com.ibm.saguaro.system.Mote;
import com.ibm.saguaro.system.Radio;
import com.ibm.saguaro.system.Util;
import com.ibm.saguaro.system.csr;

public class Routing {
	public static final byte PING = 0x00;
	public static final byte PONG = 0x01;
	public static final byte ROUTING_REQ = 0x02;
	public static final byte ROUTING = 0x03;
	public static final byte ROUTABLE_MESSAGE = 0x04;

	@SuppressWarnings("unused")
	private static Deferred ping;

	static int shortAddr;
	static int panId;

	private static Route[] routingTable = new Route[20];
	private static int knownRoutes = 0;

	static {
		shortAddr = TXRX.getShortAddr();
		panId = TXRX.getPanId();

		ping = new Deferred(new Deferred.Callback() {
			@Override
			public void invoke() {
				Routing.ping();
			}
		}, 2000);


		TXRX.addOnReceiveCallback(new TXRX.OnReceiveCallback() {
			@Override
			public void invoke(DataFrame df) {
				Routing.onReceiveFrame(df);
			}
		});
	}

	public static void ping() {
		DataFrame df = new DataFrame();
		df.setSrcAddr(shortAddr);
		df.setPanId(panId);
		df.setDestAddr(Radio.SADDR_BROADCAST);
		df.setPayload(new byte[] { PING });
		TXRX.sendDataFrame(df);
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
		Logger.appendString(csr.s2b("onMessage"));
		Logger.flush(Mote.INFO);
	}

	private static void onPing(int addr) {
		DataFrame df = new DataFrame();
		df.setSrcAddr(shortAddr);
		df.setPanId(panId);
		df.setDestAddr(addr);
		df.setPayload(new byte[] { PONG });
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
		for (int i = 0; i < ((payload.length - 1) /3); i++) {
			int addr = Util.get16le(payload, (i * 3) + 1);
			int hops = payload[(i * 3) + 3];
			shouldPropagate = shouldPropagate || updateRoutingTable(addr, df.getSrcAddr(), hops + 1);
		}

		if (shouldPropagate) {
			logRoutingTable();
			broadcastRoutingTable();
		}
	}

	private static boolean updateRoutingTable(int addr, int peerAddr, int hops) {
		if (addr == shortAddr) {
			return false;
		}
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
				} else {
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

		byte[] payload = new byte[(3 * knownRoutes) + 1]; // addr addr hops

		payload[0] = ROUTING;

		for (int i = 0; i < knownRoutes; i++) {
			Util.set16le(payload, (i * 3) + 1, routingTable[i].addr);
			payload[(i * 3) + 3] = (byte) routingTable[i].hops;
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
