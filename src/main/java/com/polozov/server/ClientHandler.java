package com.polozov.server;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

public class ClientHandler implements Runnable {
	private final Socket socket;

	public ClientHandler(Socket socket) {
		this.socket = socket;
	}

	@Override
	public void run() {
		try (
				DataOutputStream out = new DataOutputStream(socket.getOutputStream());
				DataInputStream in = new DataInputStream(socket.getInputStream())
		) {
			while (true) {
				String command = in.readUTF();
				if ("upload".equals(command)) {
					uploading(out, in);
				}
				if ("download".equals(command)) {
					// TODO: 13.05.2021 downloading
					downloading(out, in);
				}
				if ("exit".equals(command)) {
					out.writeUTF("DONE");
					disconnected();
					System.out.printf("Client %s disconnected correctly\n", socket.getInetAddress());
					break;
				}
				System.out.println(command);
				out.writeUTF(command);
			}
		} catch (SocketException socketException) {
			System.out.printf("Client %s disconnected\n", socket.getInetAddress());
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Put file on request
 	 * @param out
	 * @param in
	 */
	private void downloading(DataOutputStream out, DataInputStream in) {
		try {
			String filename = in.readUTF();
			File file = new File("server/" + filename);
			if (file.exists()) {
				long fileLength = file.length();
				FileInputStream fis = new FileInputStream(file);
				out.writeUTF(filename);
				out.writeLong(fileLength);

				int read = 0;
				byte[] buffer = new byte[8 * 1024];
				while ((read = fis.read(buffer)) != -1) {
					out.write(buffer, 0, read);
				}
				out.flush();

				String status = in.readUTF();
				System.out.println("Sending status: " + status);
			} else {
				out.writeUTF("File not found");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void uploading(DataOutputStream out, DataInputStream in) throws IOException {
		try {
			File file = new File("server/" + in.readUTF()); // read file name
			if (!file.exists()) {
				file.createNewFile();
			}

			FileOutputStream fos = new FileOutputStream(file);
			long size = in.readLong();
			byte[] buffer = new byte[8 * 1024];
			for (int i = 0; i < (size + (8 * 1024 - 1)) / (8 * 1024); i++) {
				int read = in.read(buffer);
				fos.write(buffer, 0, read);
			}
			fos.close();
			out.writeUTF("OK");
		} catch (Exception e) {
			out.writeUTF("WRONG");
		}
	}

	private void disconnected() {
		try {
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
