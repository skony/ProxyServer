
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.deploy.util.StringUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class MyProxyServer 
{
	public static void main(String[] args) throws Exception 
	{
		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		server.createContext("/", new ProxyHandler());
		System.out.println("Starting server on port: 8000");
		server.start();
	}
	
	static class ProxyHandler implements HttpHandler 
	{
		public void handle(HttpExchange exchange) throws IOException 
		{
			try {
				String reqMethod = exchange.getRequestMethod();
				URI reqUri = exchange.getRequestURI();
				String reqQuery = reqUri.toString();
				if(!checkIfAllowed(exchange, reqQuery)) {
					return;
				}

				URL reqUrl = new URL(reqQuery);
				Headers reqHeaders = exchange.getRequestHeaders();
				InputStream reqBody = exchange.getRequestBody();

				HttpURLConnection connection = (HttpURLConnection) reqUrl.openConnection();
				connection.setRequestMethod(reqMethod);
				for (Map.Entry<String, List<String>> entry : reqHeaders.entrySet()) {
					if(entry.getKey() != null) {
						if (entry.getValue().size() == 1) {
							connection.setRequestProperty(entry.getKey(), entry.getValue().get(0));
						} else if (entry.getValue().size() > 1) {
							connection.setRequestProperty(entry.getKey(), StringUtils.join(entry.getValue(), ","));
						}
					}
				}

				connection.setDoInput(true);

				if (!reqMethod.equals("GET")) {
					connection.setDoOutput(true);
					OutputStream connOutStream = connection.getOutputStream();
					writeInputToOutput(reqBody, connOutStream);
				}

				int resCode = connection.getResponseCode();
				InputStream resStream = null;
				if(resCode >399 && resCode < 600) {
					resStream = connection.getErrorStream();
				}
				else {
					resStream = connection.getInputStream();
				}

				Map<String, List<String>> resHeaders = connection.getHeaderFields();
				for (Map.Entry<String, List<String>> entry : resHeaders.entrySet()) {
					if(entry.getKey() != null) {
						if (entry.getValue().size() == 1) {
							exchange.getResponseHeaders().set(entry.getKey(), entry.getValue().get(0));
						} else if (entry.getValue().size() > 1) {
							exchange.getResponseHeaders().set(entry.getKey(), StringUtils.join(entry.getValue(), ","));
						}
					}
				}

				int estimatedData = resStream.available();
				exchange.sendResponseHeaders(resCode, 0);
				OutputStream os = exchange.getResponseBody();
				writeInputToOutput(resStream, os);
				updateStatistics(getDomainName(reqQuery), estimatedData);
			} catch(Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static void writeInputToOutput(InputStream in, OutputStream out) throws IOException {
		byte[] b = new byte[1];
		try {
			while (in.read(b) != -1) {
				out.write(b);
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		finally {
			out.close();
		}
	}

	public static String getDomainName(String url) throws URISyntaxException {
		URI uri = new URI(url);
		String domain = uri.getHost();
		return domain.startsWith("www.") ? domain.substring(4) : domain;
	}

	public static boolean checkIfAllowed(HttpExchange exchange, String query) throws IOException {
		List<String> blacklist = readFile("rsc/blacklist.txt");
		if(listContainsString(blacklist, query)) {
			String response = "PAGE YOU TRIED TO CONNECT IS ON BLACKLIST";
			byte[] bytes = response.getBytes();
			exchange.getResponseHeaders().set("Content-Type", "text/html;charset=utf-8");
			exchange.sendResponseHeaders(200, bytes.length);
			OutputStream os = exchange.getResponseBody();
			os.write(bytes);
			os.close();

			return false;
		}

		return true;
	}

	public static boolean listContainsString(List<String> list, String string) {
		for(String s : list) {
			if (string.contains(s)) {
				return true;
			}
		}

		return false;
	}

	public static List<String> readFile(String path) {

		BufferedReader br = null;
		List<String> content = new ArrayList<String>();

		try {
			String sCurrentLine;
			br = new BufferedReader(new FileReader(path));

			while((sCurrentLine = br.readLine()) != null) {
				content.add(sCurrentLine.replaceAll("\n", ""));
			}

		}
		catch (IOException e) {
			e.printStackTrace();
		}
		finally {
			try {
				if (br != null) br.close();
				//return content;
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}

		return content;
	}

	public static List<Map<String, String>> readCsvFile(String path) {
		if( !(new File(path)).exists()) {
			return null;
		}

		List<Map<String, String>> fileContent = new ArrayList<Map<String, String>>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(path));
			String sCurrentLine;
			while((sCurrentLine = br.readLine()) != null) {
				String [] words = sCurrentLine.split(",");
				Map<String, String> record = new HashMap<String, String>();
				record.put("Domain", words[0]);
				record.put("Requests", words[1]);
				record.put("Data", words[2]);
				fileContent.add(record);
			}

		} catch (IOException e) { }

		return fileContent;
	}

	public static void updateStatistics(String domain, int data) throws IOException {
		List<Map<String, String>> fileContent = readCsvFile("rsc/statistics.csv");
		if(fileContent == null) {
			PrintWriter writer = new PrintWriter("rsc/statistics.csv", "UTF-8");
			writer.println("Domain,Requests,Data");
			writer.println(domain + ",1," + Integer.toString(data));
			writer.close();
		}
		else {
			PrintWriter writer = new PrintWriter("rsc/statistics.csv", "UTF-8");
			boolean recordExists = false;
			for(Map<String, String> record : fileContent) {
				if(record.get("Domain").equals(domain)) {
					String requests = Integer.toString(Integer.valueOf(record.get("Requests")) + 1);
					String updatedData = Integer.toString(Integer.valueOf(record.get("Data")) + data);
					writer.println(domain + "," + requests + "," + updatedData);
					recordExists = true;
				}
				else {
					writer.println(record.get("Domain") + "," + record.get("Requests") + "," + record.get("Data"));
				}
			}

			if(!recordExists) {
				writer.println(domain + ",1," + Integer.toString(data));
			}

			writer.close();
		}
	}
}
