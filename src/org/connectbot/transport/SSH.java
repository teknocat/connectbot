/**
 *
 */
package org.connectbot.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.connectbot.R;
import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;
import org.connectbot.bean.PubkeyBean;
import org.connectbot.service.TerminalBridge;
import org.connectbot.service.TerminalManager;
import org.connectbot.util.HostDatabase;
import org.connectbot.util.PubkeyDatabase;
import org.connectbot.util.PubkeyUtils;

import android.util.Log;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.ConnectionMonitor;
import com.trilead.ssh2.DynamicPortForwarder;
import com.trilead.ssh2.KnownHosts;
import com.trilead.ssh2.LocalPortForwarder;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.crypto.PEMDecoder;

/**
 * @author Kenny Root
 *
 */
public class SSH extends AbsTransport implements ConnectionMonitor {
	public SSH() {
		super();
	}

	/**
	 * @param bridge
	 * @param db
	 */
	public SSH(HostBean host, TerminalBridge bridge, TerminalManager manager) {
		super(host, bridge, manager);
	}

	private static final String PROTOCOL = "ssh";
	private static final String TAG = "AbsTransport.SSH";

	private static final String AUTH_PUBLICKEY = "publickey",
		AUTH_PASSWORD = "password",
		AUTH_KEYBOARDINTERACTIVE = "keyboard-interactive";

	private final static int AUTH_TRIES = 20;

	private boolean compression = false;
	private volatile boolean authenticated = false;
	private volatile boolean connected = false;
	private volatile boolean sessionOpen = false;

	private boolean pubkeysExhausted = false;

	private Connection connection;
	private Session session;
	private ConnectionInfo connectionInfo;

	private OutputStream stdin;
	private InputStream stdout;
	private InputStream stderr;

	private static final int conditions = ChannelCondition.STDOUT_DATA
		| ChannelCondition.STDERR_DATA
		| ChannelCondition.CLOSED
		| ChannelCondition.EOF;

	private List<PortForwardBean> portForwards = new LinkedList<PortForwardBean>();

	private int columns;
	private int rows;

	private int width;
	private int height;

	public class HostKeyVerifier implements ServerHostKeyVerifier {
		public boolean verifyServerHostKey(String hostname, int port,
				String serverHostKeyAlgorithm, byte[] serverHostKey) throws IOException {

			// read in all known hosts from hostdb
			KnownHosts hosts = manager.hostdb.getKnownHosts();
			Boolean result;

			String matchName = String.format("%s:%d", hostname, port);

			String fingerprint = KnownHosts.createHexFingerprint(serverHostKeyAlgorithm, serverHostKey);

			String algorithmName;
			if ("ssh-rsa".equals(serverHostKeyAlgorithm))
				algorithmName = "RSA";
			else if ("ssh-dss".equals(serverHostKeyAlgorithm))
				algorithmName = "DSA";
			else
				algorithmName = serverHostKeyAlgorithm;

			switch(hosts.verifyHostkey(matchName, serverHostKeyAlgorithm, serverHostKey)) {
			case KnownHosts.HOSTKEY_IS_OK:
				bridge.outputLine(String.format("Verified host %s key: %s", algorithmName, fingerprint));
				return true;

			case KnownHosts.HOSTKEY_IS_NEW:
				// prompt user
				bridge.outputLine(String.format("The authenticity of host '%s' can't be established.", hostname));
				bridge.outputLine(String.format("Host %s key fingerprint is %s", algorithmName, fingerprint));

				result = bridge.getPromptHelper().requestBooleanPrompt("Are you sure you want\nto continue connecting?");
				if(result == null) return false;
				if(result.booleanValue()) {
					// save this key in known database
					manager.hostdb.saveKnownHost(hostname, port, serverHostKeyAlgorithm, serverHostKey);
				}
				return result.booleanValue();

			case KnownHosts.HOSTKEY_HAS_CHANGED:
				bridge.outputLine("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
				bridge.outputLine("@	WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!	 @");
				bridge.outputLine("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
				bridge.outputLine("IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!");
				bridge.outputLine("Someone could be eavesdropping on you right now (man-in-the-middle attack)!");
				bridge.outputLine("It is also possible that the host key has just been changed.");
				bridge.outputLine(String.format("Host %s key fingerprint is %s",
						algorithmName, fingerprint));

				// Users have no way to delete keys, so we'll prompt them for now.
				result = bridge.getPromptHelper().requestBooleanPrompt("Are you sure you want\nto continue connecting?");
				if(result == null) return false;
				if(result.booleanValue()) {
					// save this key in known database
					manager.hostdb.saveKnownHost(hostname, port, serverHostKeyAlgorithm, serverHostKey);
				}
				return result.booleanValue();

				default:
					return false;
			}
		}

	}

	/**
	 * Attempt connection with database row pointed to by cursor.
	 * @param cursor
	 * @return true for successful authentication
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 * @throws IOException
	 */
	private boolean tryPublicKey(PubkeyBean pubkey) throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
		Object trileadKey = null;
		if(manager.isKeyLoaded(pubkey.getNickname())) {
			// load this key from memory if its already there
			Log.d(TAG, String.format("Found unlocked key '%s' already in-memory", pubkey.getNickname()));
			trileadKey = manager.getKey(pubkey.getNickname());

		} else {
			// otherwise load key from database and prompt for password as needed
			String password = null;
			if (pubkey.isEncrypted()) {
				password = bridge.getPromptHelper().requestStringPrompt(
						manager.res.getString(R.string.prompt_pubkey_password, pubkey.getNickname()));

				// Something must have interrupted the prompt.
				if (password == null)
					return false;
			}

			if(PubkeyDatabase.KEY_TYPE_IMPORTED.equals(pubkey.getType())) {
				// load specific key using pem format
				trileadKey = PEMDecoder.decode(new String(pubkey.getPrivateKey()).toCharArray(), password);
			} else {
				// load using internal generated format
				PrivateKey privKey;
				try {
					privKey = PubkeyUtils.decodePrivate(pubkey.getPrivateKey(),
							pubkey.getType(), password);
				} catch (Exception e) {
					String message = String.format("Bad password for key '%s'. Authentication failed.", pubkey.getNickname());
					Log.e(TAG, message, e);
					bridge.outputLine(message);
					return false;
				}

				PublicKey pubKey = PubkeyUtils.decodePublic(pubkey.getPublicKey(),
						pubkey.getType());

				// convert key to trilead format
				trileadKey = PubkeyUtils.convertToTrilead(privKey, pubKey);
				Log.d(TAG, "Unlocked key " + PubkeyUtils.formatKey(pubKey));
			}

			Log.d(TAG, String.format("Unlocked key '%s'", pubkey.getNickname()));

			// save this key in-memory if option enabled
			if(manager.isSavingKeys()) {
				manager.addKey(pubkey.getNickname(), trileadKey);
			}
		}

		return tryPublicKey(host.getUsername(), pubkey.getNickname(), trileadKey);
	}

	private boolean tryPublicKey(String username, String keyNickname, Object trileadKey) throws IOException {
		//bridge.outputLine(String.format("Attempting 'publickey' with key '%s' [%s]...", keyNickname, trileadKey.toString()));
		boolean success = connection.authenticateWithPublicKey(username, trileadKey);
		if(!success)
			bridge.outputLine(String.format("Authentication method 'publickey' with key '%s' failed", keyNickname));
		return success;
	}


	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#authenticate()
	 */
	private void authenticate() {
		try {
			if (connection.authenticateWithNone(host.getUsername())) {
				finishConnection();
				return;
			}
		} catch(Exception e) {
			Log.d(TAG, "Host does not support 'none' authentication.");
		}

		bridge.outputLine("Trying to authenticate");

		try {
			long pubkeyId = host.getPubkeyId();

			if (!pubkeysExhausted &&
					pubkeyId != HostDatabase.PUBKEYID_NEVER &&
					connection.isAuthMethodAvailable(host.getUsername(), AUTH_PUBLICKEY)) {

				// if explicit pubkey defined for this host, then prompt for password as needed
				// otherwise just try all in-memory keys held in terminalmanager

				if (pubkeyId == HostDatabase.PUBKEYID_ANY) {
					// try each of the in-memory keys
					bridge.outputLine(manager.res
							.getString(R.string.terminal_auth_pubkey_any));
					for(String nickname : manager.loadedPubkeys.keySet()) {
						Object trileadKey = manager.loadedPubkeys.get(nickname);
						if(this.tryPublicKey(host.getUsername(), nickname, trileadKey)) {
							finishConnection();
							break;
						}
					}
				} else {
					bridge.outputLine("Attempting 'publickey' authentication with a specific public key");
					// use a specific key for this host, as requested
					PubkeyBean pubkey = manager.pubkeydb.findPubkeyById(pubkeyId);

					if (pubkey == null)
						bridge.outputLine(manager.res.getString(R.string.terminal_auth_pubkey_invalid));
					else
						if (tryPublicKey(pubkey))
							finishConnection();
				}

				pubkeysExhausted = true;
			} else if (connection.isAuthMethodAvailable(host.getUsername(), AUTH_PASSWORD)) {
				bridge.outputLine(manager.res.getString(R.string.terminal_auth_pass));
				String password = bridge.getPromptHelper().requestStringPrompt(
						manager.res.getString(R.string.terminal_auth_pass_hint));
				if (password != null
						&& connection.authenticateWithPassword(host.getUsername(), password)) {
					finishConnection();
				} else {
					bridge.outputLine(manager.res.getString(R.string.terminal_auth_pass_fail));
				}
			} else if(connection.isAuthMethodAvailable(host.getUsername(), AUTH_KEYBOARDINTERACTIVE)) {
				// this auth method will talk with us using InteractiveCallback interface
				// it blocks until authentication finishes
				bridge.outputLine(manager.res.getString(R.string.terminal_auth_ki));
				if(connection.authenticateWithKeyboardInteractive(host.getUsername(), bridge)) {
					finishConnection();
				} else {
					bridge.outputLine(manager.res.getString(R.string.terminal_auth_ki_fail));
				}
			} else {
				bridge.outputLine(manager.res.getString(R.string.terminal_auth_fail));
			}
		} catch(Exception e) {
			Log.e(TAG, "Problem during handleAuthentication()", e);
		}
	}

	/**
	 * Internal method to request actual PTY terminal once we've finished
	 * authentication. If called before authenticated, it will just fail.
	 */
	private void finishConnection() {
		authenticated = true;

		// Start up predefined port forwards
		for (PortForwardBean pfb : portForwards) {
			try {
				enablePortForward(pfb);
				bridge.outputLine(String.format("Enable port forward: %s", pfb.getDescription()));
			} catch (Exception e) {
				Log.e(TAG, "Error setting up port forward during connect", e);
			}
		}

		if (!host.getWantSession()) {
			bridge.outputLine("Session will not be started due to host preference.");
			return;
		}

		try {
			session = connection.openSession();

			session.requestPTY(getEmulation(), columns, rows, width, height, null);
			session.startShell();

			stdin = session.getStdin();
			stdout = session.getStdout();
			stderr = session.getStderr();

			sessionOpen = true;

			bridge.onConnected();
		} catch (IOException e1) {
			Log.e(TAG, "Problem while trying to create PTY in finishConnection()", e1);
		}

	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#connect(java.lang.String, int)
	 */
	@Override
	public void connect() {
		connection = new Connection(host.getHostname(), host.getPort());
		connection.addConnectionMonitor(this);
		//connection.setCompression(compression);

		new Thread(new Runnable() {
			public void run() {
				try {
					/* Uncomment when debugging SSH protocol:
					DebugLogger logger = new DebugLogger() {

						public void log(int level, String className, String message) {
							Log.d("SSH", message);
						}

					};
					Logger.enabled = true;
					Logger.logger = logger;
					*/
					connectionInfo = connection.connect(new HostKeyVerifier());
					connected = true;

					if (connectionInfo.clientToServerCryptoAlgorithm
							.equals(connectionInfo.serverToClientCryptoAlgorithm)
							&& connectionInfo.clientToServerMACAlgorithm
									.equals(connectionInfo.serverToClientMACAlgorithm)) {
						bridge.outputLine(String.format("Using algorithm: %s %s",
								connectionInfo.clientToServerCryptoAlgorithm,
								connectionInfo.clientToServerMACAlgorithm));
					} else {
						bridge.outputLine(String.format(
								"Client-to-server algorithm: %s %s",
								connectionInfo.clientToServerCryptoAlgorithm,
								connectionInfo.clientToServerMACAlgorithm));

						bridge.outputLine(String.format(
								"Server-to-client algorithm: %s %s",
								connectionInfo.serverToClientCryptoAlgorithm,
								connectionInfo.serverToClientMACAlgorithm));
					}
				} catch (IOException e) {
					Log.e(TAG, "Problem in SSH connection thread during authentication", e);

					// Display the reason in the text.
					bridge.outputLine(e.getCause().getMessage());

					onDisconnect();
					return;
				}

				try {
					// enter a loop to keep trying until authentication
					int tries = 0;
					while(!connection.isAuthenticationComplete() && tries++ < AUTH_TRIES) {
						authenticate();

						// sleep to make sure we dont kill system
						Thread.sleep(1000);
					}
				} catch(Exception e) {
					Log.e(TAG, "Problem in SSH connection thread during authentication", e);
				}
			}
		}).start();
	}

	@Override
	public int read(byte[] buffer, int start, int len) throws IOException{
		int bytesRead = 0;

		int newConditions = session.waitForCondition(conditions, 0);

		if ((newConditions & ChannelCondition.STDOUT_DATA) != 0) {
			bytesRead = stdout.read(buffer, start, len);
		}

		if ((newConditions & ChannelCondition.STDERR_DATA) != 0) {
			byte discard[] = new byte[256];
			while (stderr.available() > 0) {
				stderr.read(discard);
			}
		}

		if ((newConditions & ChannelCondition.EOF) != 0) {
			onDisconnect();
			throw new IOException("Remote end closed connection");
		}

		return bytesRead;
	}

	/**
	 *
	 */
	private void onDisconnect() {
		connected = false;
		bridge.dispatchDisconnect(false);
	}


	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#flush()
	 */
	@Override
	public void flush() throws IOException {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#write(byte[])
	 */
	@Override
	public void write(byte[] buffer) throws IOException {
		stdin.write(buffer);
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#write(char)
	 */
	@Override
	public void write(int c) throws IOException {
		stdin.write(c);
	}
	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#getOptions()
	 */
	@Override
	public Map<String, String> getOptions() {
		Map<String, String> options = new HashMap<String, String>();

		options.put("compression", Boolean.toString(compression));

		return options;
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#getProtocolName()
	 */
	public static String getProtocolName() {
		return PROTOCOL;
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#isSessionOpen()
	 */
	@Override
	public boolean isSessionOpen() {
		return sessionOpen;
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#isConnected()
	 */
	@Override
	public boolean isConnected() {
		return connected;
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#setOptions(java.util.Map)
	 */
	@Override
	public void setOptions(Map<String, String> options) {
		if (options.containsKey("compression"))
			compression = Boolean.parseBoolean(options.get("compression"));
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#addPortForward(org.connectbot.bean.PortForwardBean)
	 */
	@Override
	public boolean addPortForward(PortForwardBean portForward) {
		return portForwards.add(portForward);
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#removePortForward(org.connectbot.bean.PortForwardBean)
	 */
	@Override
	public boolean removePortForward(PortForwardBean portForward) {
		// Make sure we don't have a phantom forwarder.
		disablePortForward(portForward);

		return portForwards.remove(portForward);
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#enablePortForward(org.connectbot.bean.PortForwardBean)
	 */
	@Override
	public boolean enablePortForward(PortForwardBean portForward) {
		if (!portForwards.contains(portForward)) {
			Log.e(TAG, "Attempt to enable port forward not in list");
			return false;
		}

		if (HostDatabase.PORTFORWARD_LOCAL.equals(portForward.getType())) {
			LocalPortForwarder lpf = null;
			try {
				lpf = connection.createLocalPortForwarder(portForward.getSourcePort(), portForward.getDestAddr(), portForward.getDestPort());
			} catch (IOException e) {
				Log.e(TAG, "Could not create local port forward", e);
				return false;
			}

			if (lpf == null) {
				Log.e(TAG, "returned LocalPortForwarder object is null");
				return false;
			}

			portForward.setIdentifier(lpf);
			portForward.setEnabled(true);
			return true;
		} else if (HostDatabase.PORTFORWARD_REMOTE.equals(portForward.getType())) {
			try {
				connection.requestRemotePortForwarding("", portForward.getSourcePort(), portForward.getDestAddr(), portForward.getDestPort());
			} catch (IOException e) {
				Log.e(TAG, "Could not create remote port forward", e);
				return false;
			}

			portForward.setEnabled(false);
			return true;
		} else if (HostDatabase.PORTFORWARD_DYNAMIC5.equals(portForward.getType())) {
			DynamicPortForwarder dpf = null;

			try {
				dpf = connection.createDynamicPortForwarder(portForward.getSourcePort());
			} catch (IOException e) {
				Log.e(TAG, "Could not create dynamic port forward", e);
				return false;
			}

			portForward.setIdentifier(dpf);
			portForward.setEnabled(true);
			return true;
		} else {
			// Unsupported type
			Log.e(TAG, String.format("attempt to forward unknown type %s", portForward.getType()));
			return false;
		}
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#disablePortForward(org.connectbot.bean.PortForwardBean)
	 */
	@Override
	public boolean disablePortForward(PortForwardBean portForward) {
		if (!portForwards.contains(portForward)) {
			Log.e(TAG, "Attempt to disable port forward not in list");
			return false;
		}

		if (HostDatabase.PORTFORWARD_LOCAL.equals(portForward.getType())) {
			LocalPortForwarder lpf = null;
			lpf = (LocalPortForwarder)portForward.getIdentifier();

			if (!portForward.isEnabled() || lpf == null) {
				Log.d(TAG, String.format("Could not disable %s; it appears to be not enabled or have no handler", portForward.getNickname()));
				return false;
			}

			portForward.setEnabled(false);

			try {
				lpf.close();
			} catch (IOException e) {
				Log.e(TAG, "Could not stop local port forwarder, setting enabled to false", e);
				return false;
			}

			return true;
		} else if (HostDatabase.PORTFORWARD_REMOTE.equals(portForward.getType())) {
			portForward.setEnabled(false);

			try {
				connection.cancelRemotePortForwarding(portForward.getSourcePort());
			} catch (IOException e) {
				Log.e(TAG, "Could not stop remote port forwarding, setting enabled to false", e);
				return false;
			}

			return true;
		} else if (HostDatabase.PORTFORWARD_DYNAMIC5.equals(portForward.getType())) {
			DynamicPortForwarder dpf = null;
			dpf = (DynamicPortForwarder)portForward.getIdentifier();

			if (!portForward.isEnabled() || dpf == null) {
				Log.d(TAG, String.format("Could not disable %s; it appears to be not enabled or have no handler", portForward.getNickname()));
				return false;
			}

			portForward.setEnabled(false);

			try {
				dpf.close();
			} catch (IOException e) {
				Log.e(TAG, "Could not stop dynamic port forwarder, setting enabled to false", e);
				return false;
			}

			return true;
		} else {
			// Unsupported type
			Log.e(TAG, String.format("attempt to forward unknown type %s", portForward.getType()));
			return false;
		}
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#canForwardPorts()
	 */
	@Override
	public boolean canForwardPorts() {
		return true;
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#getPortForwards()
	 */
	@Override
	public List<PortForwardBean> getPortForwards() {
		return portForwards;
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#setDimensions(int, int, int, int)
	 */
	@Override
	public void setDimensions(int columns, int rows, int width, int height) {
		this.columns = columns;
		this.rows = rows;

		if (sessionOpen) {
			try {
				session.resizePTY(columns, rows, width, height);
			} catch (IOException e) {
				Log.e(TAG, "Couldn't send resize PTY packet", e);
			}
		}
	}

	/* (non-Javadoc)
	 * @see com.trilead.ssh2.ConnectionMonitor#connectionLost(java.lang.Throwable)
	 */
	public void connectionLost(Throwable reason) {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#close()
	 */
	@Override
	public void close() {
		if (session != null)
			session.close();
		if (connection != null)
			connection.close();
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#getDefaultPort()
	 */
	@Override
	public int getDefaultPort() {
		return 22;
	}

	@Override
	public String getDefaultNickname(String username, String hostname, int port) {
		if (port == 22) {
			return String.format("%s@%s", username, hostname);
		} else {
			return String.format("%s@%s:%d", username, hostname, port);
		}
	}
}