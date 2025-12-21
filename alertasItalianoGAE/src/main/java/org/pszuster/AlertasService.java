package org.pszuster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.http.entity.StringEntity;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Path("/alertas")
public class AlertasService {
	
	@Context
	private HttpServletRequest httpRequest;
	
	DatastoreService datastore;
	private static final Logger log = Logger.getLogger(AlertasService.class.getName());
			
	@GET
	@Path("/scheduler")
	public Response execSchedule() {
		String especialidad="";
	    try {
	    
	    	datastore = DatastoreServiceFactory.getDatastoreService();    	
	    	Query q = new Query("Alerta");
	    	PreparedQuery pq = datastore.prepare(q);
	    	List<Entity> alertas = pq.asList(FetchOptions.Builder.withOffset(0));
	    	Iterator<Entity> iter = alertas.iterator();
	    	while(iter.hasNext()) {	    		
	    		Entity alerta = iter.next();
	    		Entity usuario = datastore.get(alerta.getParent());	
	    		CookieManager cookieManager = new CookieManager();
	    		CookieHandler.setDefault(cookieManager);
		    	//Login body
		    	String json = "{\"tipoDocumento\":\"" + usuario.getProperty("tipoDocumento") + "\",\"numeroDocumento\":\"" +  usuario.getProperty("numeroDocumento") + "\",\"password\":\"" + usuario.getProperty("password") + "\"}";
		    	StringEntity entity = new StringEntity(json);
		    	
		    	//obtengo cookie JSESSIONID inicial
	    		String rtdoHome = httpGet("https://www1.hospitalitaliano.org.ar/PortalWeb/");
				
				//Login
	    		URL urlLogin = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/login" );
	    		HttpURLConnection connLogin = (HttpURLConnection) urlLogin.openConnection();
	    		connLogin.setDoOutput(true);
				connLogin.setRequestProperty("Content-Type", "application/json");
				connLogin.setRequestMethod("POST");
				connLogin.setConnectTimeout(30000);
				OutputStreamWriter writer = new OutputStreamWriter(connLogin.getOutputStream());
			    writer.write(json);
			    writer.close();
			    StringBuffer responseLogin = new StringBuffer();
			    String lineLogin;
			     BufferedReader readerLogin = new BufferedReader(new InputStreamReader(connLogin.getInputStream()));
			      while ((lineLogin = readerLogin.readLine()) != null) {
			    	  responseLogin.append(lineLogin);
			      }
			      readerLogin.close();
				JsonParser jsonparser = new JsonParser();
				JsonObject loginResponseObj = jsonparser.parse(responseLogin.toString()).getAsJsonObject();
				
				// Get the public key and add PEM headers if not present
				String publicKey = loginResponseObj.get("publicKey").getAsString();
				if (!publicKey.contains("BEGIN PUBLIC KEY")) {
					publicKey = "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----";
				}
				
				String lugaresAtencionStr="";
				List<String> lugaresAtencion = Arrays.asList(alerta.getProperty("lugares").toString().split(","));
				for (String lugar: lugaresAtencion) {
					lugaresAtencionStr+="&lugarAtencionIds=" + lugar;
				}
				//Busco primeros 10 turnos							

				String pacienteId ="";
				Object pacienteIdObj = alerta.getProperty("pacienteId");
				if(pacienteIdObj != null){
					 pacienteId = alerta.getProperty("pacienteId").toString();
				}
				else{
					pacienteId = loginResponseObj.get("id").toString() ;
				}
				
			
				// Build query strings with proper URL encoding (matching JavaScript behavior)
				String turnosBaseUrl = "https://www1.hospitalitaliano.org.ar/wssPortal/api/turnos/reserva/buscar";
				String turnosQueryString;
				
				if (alerta.getProperty("tipoAlerta").equals("nombre")) {
					// Build query string for searching by doctor name
					turnosQueryString = "esMiMedico=" + java.net.URLEncoder.encode("false", "UTF-8") +
						"&idPersonaFederada=" + java.net.URLEncoder.encode(pacienteId, "UTF-8") +
						"&limit=" + java.net.URLEncoder.encode("10", "UTF-8") +
						lugaresAtencionStr +
						"&medicoId=" + java.net.URLEncoder.encode(alerta.getProperty("nombre").toString(), "UTF-8") +
						"&pageInit=" + java.net.URLEncoder.encode("0", "UTF-8");
				} else {
					// Build query string for searching by specialty
					turnosQueryString = "esMiMedico=" + java.net.URLEncoder.encode("false", "UTF-8") +
						"&especialidadId=" + java.net.URLEncoder.encode(alerta.getProperty("especialidad").toString(), "UTF-8") +
						"&idPersonaFederada=" + java.net.URLEncoder.encode(pacienteId, "UTF-8") +
						"&limit=" + java.net.URLEncoder.encode("10", "UTF-8") +
						lugaresAtencionStr +
						"&pageInit=" + java.net.URLEncoder.encode("0", "UTF-8");
				}
				URL urlTurnos = new URL(turnosBaseUrl + "?" + turnosQueryString);
				
				HttpURLConnection connTurnos = (HttpURLConnection) urlTurnos.openConnection();
				connTurnos.addRequestProperty("x-auth-token", loginResponseObj.get("perfil").getAsJsonObject().get("token").getAsString());
				
				// Encrypt the API path for midUtssid - match JavaScript behavior
				String apiPath = "/api/turnos/reserva/buscar?" + turnosQueryString;
				String encryptedMidUtssid = encryptWithPublicKey(apiPath, publicKey);
				connTurnos.addRequestProperty("midUtssid", encryptedMidUtssid);
				connTurnos.addRequestProperty("origen", "WEB");
				connTurnos.addRequestProperty("so", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
				 		connTurnos.setConnectTimeout(30000);
	    		BufferedReader readerTurnos = new BufferedReader(new InputStreamReader(connTurnos.getInputStream()));
	    	    StringBuffer jsonTurnosSB = new StringBuffer();
	    	    String lineTurnos;

	    	    while ((lineTurnos = readerTurnos.readLine()) != null) {
	    	    	jsonTurnosSB.append(lineTurnos);
	    	    }
	    	    readerTurnos.close();
	    	    String turnosStr = jsonTurnosSB.toString();	

				log.info( turnosStr);

				JsonArray turnosObjArr = jsonparser.parse(turnosStr).getAsJsonArray();
				 				 
				 //Comparo útlimos tunos encontrados vs actuales
				 String lastFoundStr = alerta.getProperty("lastfound").toString();
				 long lastFoundFirstDate= Long.MAX_VALUE;
				 if(!lastFoundStr.equals("")) {
					 int commaIndex=lastFoundStr.indexOf(',');
				 	 if(commaIndex>0)
				 		 //hay más de un turno
				 		lastFoundFirstDate= new Long(lastFoundStr.substring(0,commaIndex )).longValue();
				 	 else
				 		 //un solo turno
				 		lastFoundFirstDate = new Long(lastFoundStr).longValue();
				 }
				 	 
				 if(turnosObjArr.size()<1){
					continue;
				 }
				 
				 long curFirstDate = turnosObjArr.get(0).getAsJsonObject().get("fechaTurno").getAsLong();
				 if( curFirstDate != lastFoundFirstDate){
					 //tengo turno anterior al registrado
					 String newLastFoundStr="";
					 especialidad = turnosObjArr.get(0).getAsJsonObject().get("especialidad").getAsString();
					 String notificacion="Próximos turnos encontrados para " + especialidad + ": <br><br><table style=\"border: 1px solid darkolivegreen;border-collapse: collapse;\">";
					 int i=0;
					 for (JsonElement turno: turnosObjArr) {
						 i++;
						 JsonObject turnoObj = turno.getAsJsonObject();
						 long fechaTurno = turnoObj.get("fechaTurno").getAsLong();
						 if(i!= turnosObjArr.size())
							 newLastFoundStr= newLastFoundStr + fechaTurno + ",";
						 else
							 newLastFoundStr= newLastFoundStr + fechaTurno;
						 
						 SimpleDateFormat sdf =  new SimpleDateFormat("EEE dd MMM yyyy HH:mm");
						 sdf.setTimeZone(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
						 notificacion = notificacion + "<tr style=\"border: 1px solid darkolivegreen;padding: 5px;\"><td style=\"border: 1px solid darkolivegreen;padding: 5px;\">" + sdf.format(new Date(fechaTurno)) + "</td><td style=\"border: 1px solid darkolivegreen;padding: 5px;\">" + turnoObj.get("lugar").getAsString() + "</td><td style=\"border: 1px solid darkolivegreen;padding: 5px;\">" + turnoObj.get("profesional").getAsString() + "</td></tr>"; 
					 }
					 notificacion = notificacion + "</table>";
					 notificacion = notificacion + "<br><br>" + "<a href=\"https://alertas-italiano.appspot.com\">Gestionar Alertas</a>";
					 
				
					 String email = usuario.getProperty("mail").toString();
					 
					 alerta.setProperty("lastfound", newLastFoundStr);
					 datastore.put(alerta);
					 Properties props = new Properties();
					 Session session = Session.getDefaultInstance(props, null);

					 
					 for (int tryCount=0; tryCount < 5; tryCount++) {
						 try {

						 Message message = new MimeMessage(session);
						 message.setFrom(new InternetAddress("alertas@alertas-italiano.appspotmail.com"));	
						 message.setRecipients(Message.RecipientType.TO,
									InternetAddress.parse(email));
							message.setSubject("Nuevos turnos de " + especialidad);
							message.setContent(notificacion, "text/html; charset=utf-8");
							log.info("por mandar mail");
							Transport.send(message);
							log.info("Mail enviado");
							return Response.status(200).build();
						 }catch (MessagingException e) {
								e.printStackTrace();
								try {
								Thread.sleep(20000);
								}catch(Exception ex) {
									ex.printStackTrace();
									return Response.status(500).entity(e.getStackTrace().toString()).build();
								}
							}
					 }
				 }
		    }
	    } catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity(e.getStackTrace().toString()).build();
		} 
	    
	
				

		return Response.status(200).build();
	}
	
	@GET
	@Path("/all")
	@Produces("application/json")
	public Response getAlertas(@QueryParam("tipoDoc") String tipodoc, @QueryParam("numeroDoc") String numdoc ) {
		
		String response=null;
		try {		
		    
		    datastore = DatastoreServiceFactory.getDatastoreService();
		    Filter f1 = new FilterPredicate("numeroDocumento",FilterOperator.EQUAL,numdoc);
		    Filter f2 = new FilterPredicate("tipoDocumento",FilterOperator.EQUAL,tipodoc);
		    Filter filtro = CompositeFilterOperator.and(f1,f2);
		    Query q = new Query("Usuario").setFilter(filtro);
		    PreparedQuery pq = datastore.prepare(q);
			Entity usuario = pq.asSingleEntity();
		   if(usuario!=null) {
			   Query q2 = new Query("Alerta").setAncestor(usuario.getKey());
			   PreparedQuery pq2 = datastore.prepare(q2);
			   response = convertToJSON(usuario,pq2.asList(FetchOptions.Builder.withOffset(0))).toString();
		   }else {
			   response = "{}";
		   }
		    
		} catch (IOException | SQLException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return Response.status(200).entity(response).build();
	}
	
	
	
	@POST
	@Path("")
	@Produces("application/json")
	public Response createAlert(String datosAlertaStr) {
		
		
		 JsonParser jsonparser = new JsonParser();
		 JsonObject datosAlerta = jsonparser.parse(datosAlertaStr).getAsJsonObject();
		 
		 String dni = datosAlerta.get("numeroDocumento").getAsString() ;
		 String tipodoc = datosAlerta.get("tipoDocumento").getAsString() ;
		 String mail = datosAlerta.get("mail").getAsString() ;
		 
		 try { 		    
		    datastore = DatastoreServiceFactory.getDatastoreService();
		    Entity usuario = new Entity("Usuario",dni);
		    usuario.setProperty("numeroDocumento",dni);
		    usuario.setProperty("tipoDocumento", tipodoc);
		    usuario.setProperty("mail",mail);
		    usuario.setProperty("password", datosAlerta.get("pwd").getAsString());
		    datastore.put(usuario);
		    
		    String lugares = datosAlerta.get("lugares").toString().replaceAll("[\\[\\]\"]","") ;
		    
		     Entity alerta = new Entity("Alerta",usuario.getKey());
		     alerta.setProperty("tipoAlerta",datosAlerta.get("tipoAlerta").getAsString());
		     if(datosAlerta.get("tipoAlerta").getAsString().equals("nombre")) {
		    	 alerta.setProperty( "nombre",  datosAlerta.get("nombre").getAsInt());
		    	 alerta.setProperty( "nombreCompleto",  datosAlerta.get("nombreCompleto").getAsString());
		     }
		     else
		    	 alerta.setProperty("especialidad", datosAlerta.get("especialidad").getAsInt());
		     alerta.setProperty("lugares", lugares);
		     alerta.setProperty("lastfound", "");
			 alerta.setProperty("pacienteId", datosAlerta.get("pacienteId").getAsInt());
		     datastore.put(alerta);
		     execSchedule();
		} catch ( Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": '"+ e.getLocalizedMessage() + "'}").build();
		}
		
		return Response.status(200).entity("{\"status\": \"OK\"}").build();
		
	}
	
	@DELETE
	@Path("")
	@Produces("application/json")
	public Response deleteAlert(@QueryParam("alertaID")  Long alertaID, @QueryParam("numeroDoc") String numeroDoc) {


		 try {
			 datastore = DatastoreServiceFactory.getDatastoreService();
			 datastore.delete(com.google.appengine.api.datastore.KeyFactory.createKey(com.google.appengine.api.datastore.KeyFactory.createKey("Usuario",numeroDoc),"Alerta", alertaID));
		 }catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": '"+ e.getLocalizedMessage() + "'}").build();
		}
		return Response.status(200).entity("{\"status\": \"OK\"}").build();
		
	}
	
	@POST
	@Path("/login")
	@Produces("application/json")
	public Response login(String loginDataStr) {
		try {
			JsonParser jsonparser = new JsonParser();
			JsonObject loginData = jsonparser.parse(loginDataStr).getAsJsonObject();
			
			String tipoDocumento = loginData.get("tipoDocumento").getAsString();
			String numeroDocumento = loginData.get("numeroDocumento").getAsString();
			String password = loginData.get("password").getAsString();
			
			CookieManager cookieManager = new CookieManager();
			CookieHandler.setDefault(cookieManager);
			
			// First, get initial session cookies from the main portal
			String initialCookies = "";
			
			// Call main portal to get session cookies like PPSNEW and pls_wss
			URL urlPortal = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/");
			HttpURLConnection connPortal = (HttpURLConnection) urlPortal.openConnection();
			connPortal.setConnectTimeout(30000);
			connPortal.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			
			Map<String, List<String>> portalHeaders = connPortal.getHeaderFields();
			
			// Read portal response to complete the request
			BufferedReader portalReader = new BufferedReader(new InputStreamReader(connPortal.getInputStream()));
			String portalResponse;
			while ((portalResponse = portalReader.readLine()) != null) {
				// Just consume the response
			}
			portalReader.close();
			
			// Get session cookies from portal response
			List<String> portalCookiesHeader = portalHeaders.get("set-cookie");
			if (portalCookiesHeader == null) {
				portalCookiesHeader = portalHeaders.get("Set-Cookie");
			}
			
			if (portalCookiesHeader != null) {
				for (String cookie : portalCookiesHeader) {
					String cookieValue = cookie.split(";")[0]; // Get only the name=value part
					if (initialCookies.length() > 0) {
						initialCookies += "; ";
					}
					initialCookies += cookieValue;
				}
			}
			
			// Also get PPSWSS from seteosVarios endpoint
			URL urlSeteos = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/seteosVarios/TELEDIAGNOSTICO_ACTIVADO");
			HttpURLConnection connSeteos = (HttpURLConnection) urlSeteos.openConnection();
			connSeteos.setConnectTimeout(30000);
			connSeteos.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connSeteos.setRequestProperty("Accept", "application/json, text/plain, */*");
			connSeteos.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en-US;q=0.8,en;q=0.7");
			if (!initialCookies.isEmpty()) {
				connSeteos.setRequestProperty("Cookie", initialCookies);
			}
			
			Map<String, List<String>> seteosHeaders = connSeteos.getHeaderFields();
			
			// Read seteos response to complete the request
			BufferedReader seteosReader = new BufferedReader(new InputStreamReader(connSeteos.getInputStream()));
			String seteosResponse;
			while ((seteosResponse = seteosReader.readLine()) != null) {
				// Just consume the response
			}
			seteosReader.close();
			
			// Add PPSWSS cookie from seteos response
			List<String> seteosCookiesHeader = seteosHeaders.get("set-cookie");
			if (seteosCookiesHeader == null) {
				seteosCookiesHeader = seteosHeaders.get("Set-Cookie");
			}
			
			if (seteosCookiesHeader != null) {
				for (String cookie : seteosCookiesHeader) {
					String cookieValue = cookie.split(";")[0]; // Get only the name=value part
					if (initialCookies.length() > 0) {
						initialCookies += "; ";
					}
					initialCookies += cookieValue;
				}
			}
			
			// Login body
			String json = "{\"tipoDocumento\":\"" + tipoDocumento + "\",\"numeroDocumento\":\"" + numeroDocumento + "\",\"password\":\"" + password + "\"}";
			
			// Login with JSESSIONID cookie
			URL urlLogin = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/login");
			HttpURLConnection connLogin = (HttpURLConnection) urlLogin.openConnection();
			connLogin.setDoOutput(true);
			connLogin.setRequestProperty("Content-Type", "application/json");
			connLogin.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connLogin.setRequestProperty("Referer", "https://www1.hospitalitaliano.org.ar/PortalWeb/");
			connLogin.setRequestProperty("Origin", "https://www1.hospitalitaliano.org.ar");
			connLogin.setRequestProperty("Accept", "application/json, text/plain, */*");
			connLogin.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en-US;q=0.8,en;q=0.7");
			if (!initialCookies.isEmpty()) {
				connLogin.setRequestProperty("Cookie", initialCookies);
			}
			connLogin.setRequestMethod("POST");
			connLogin.setConnectTimeout(30000);
			OutputStreamWriter writer = new OutputStreamWriter(connLogin.getOutputStream());
			writer.write(json);
			writer.close();
			
			StringBuffer responseLogin = new StringBuffer();
			String lineLogin;
			BufferedReader readerLogin = new BufferedReader(new InputStreamReader(connLogin.getInputStream()));
			while ((lineLogin = readerLogin.readLine()) != null) {
				responseLogin.append(lineLogin);
			}
			readerLogin.close();
			
			// Return the login response directly to the frontend
			return Response.status(200).entity(responseLogin.toString()).build();
			
		} catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": \"" + e.getLocalizedMessage() + "\"}").build();
		}
	}
	
	@GET
	@Path("/especialidades")
	@Produces("application/json")
	public Response getEspecialidades(@QueryParam("tipoDoc") String tipodoc, @QueryParam("numeroDoc") String numdoc, @QueryParam("password") String password, @QueryParam("idUsuario") String idUsuario, @QueryParam("midUtssid") String midUtssid) {
		
		try {
			CookieManager cookieManager = new CookieManager();
			CookieHandler.setDefault(cookieManager);
			
			// First, get initial session cookies from the main portal
			String initialCookies = "";
			
			// Try multiple endpoints to get session cookies
			// First try the main portal
			URL urlPortal = new URL("https://www1.hospitalitaliano.org.ar/PortalWeb/");
			HttpURLConnection connPortal = (HttpURLConnection) urlPortal.openConnection();
			connPortal.setConnectTimeout(30000);
			connPortal.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			
			Map<String, List<String>> portalHeaders = connPortal.getHeaderFields();
			
			// Read portal response to complete the request
			BufferedReader portalReader = new BufferedReader(new InputStreamReader(connPortal.getInputStream()));
			String portalResponse;
			while ((portalResponse = portalReader.readLine()) != null) {
				// Just consume the response
			}
			portalReader.close();
			
			// Get session cookies from portal response
			List<String> portalCookiesHeader = portalHeaders.get("set-cookie");
			if (portalCookiesHeader == null) {
				portalCookiesHeader = portalHeaders.get("Set-Cookie");
			}
			
			if (portalCookiesHeader != null) {
				for (String cookie : portalCookiesHeader) {
					String cookieValue = cookie.split(";")[0]; // Get only the name=value part
					if (initialCookies.length() > 0) {
						initialCookies += "; ";
					}
					initialCookies += cookieValue;
				}
			}
			
			// If we didn't get the required cookies, try the wssPortal endpoint
			if (!initialCookies.contains("PPSNEW") || !initialCookies.contains("pls_wss")) {
				URL urlWssPortal = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/");
				HttpURLConnection connWssPortal = (HttpURLConnection) urlWssPortal.openConnection();
				connWssPortal.setConnectTimeout(30000);
				connWssPortal.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
				
				Map<String, List<String>> wssPortalHeaders = connWssPortal.getHeaderFields();
				
				// Read wssPortal response
				BufferedReader wssPortalReader = new BufferedReader(new InputStreamReader(connWssPortal.getInputStream()));
				String wssPortalResponse;
				while ((wssPortalResponse = wssPortalReader.readLine()) != null) {
					// Just consume the response
				}
				wssPortalReader.close();
				
				// Add cookies from wssPortal
				List<String> wssPortalCookiesHeader = wssPortalHeaders.get("set-cookie");
				if (wssPortalCookiesHeader == null) {
					wssPortalCookiesHeader = wssPortalHeaders.get("Set-Cookie");
				}
				
				if (wssPortalCookiesHeader != null) {
					for (String cookie : wssPortalCookiesHeader) {
						String cookieValue = cookie.split(";")[0];
						if (initialCookies.length() > 0) {
							initialCookies += "; ";
						}
						initialCookies += cookieValue;
					}
				}
			}
			
			System.out.println("Initial cookies after portal calls: " + initialCookies);
			
			// If we still don't have the required session cookies, add them manually
			// These are the cookies from the working request
			if (!initialCookies.contains("PPSNEW")) {
				if (initialCookies.length() > 0) {
					initialCookies += "; ";
				}
				initialCookies += "PPSNEW=PPS2";
			}
			if (!initialCookies.contains("pls_wss")) {
				if (initialCookies.length() > 0) {
					initialCookies += "; ";
				}
				initialCookies += "pls_wss=PS_WSS_08GF5";
			}
			
			// Add Google Analytics cookies that were in the working request
			if (initialCookies.length() > 0) {
				initialCookies += "; ";
			}
			initialCookies += "_gcl_au=1.1.402083268.1761531164; _ga=GA1.1.839463160.1761531164; _ga_C0ZP3ZFYE3=GS2.1.s1761531164$o1$g0$t1761531171$j53$l0$h0";
			
			System.out.println("Final initial cookies with required session cookies: " + initialCookies);
			
			// Don't add PPSWSS manually if we already got it from the server
			if (!initialCookies.contains("PPSWSS")) {
				if (initialCookies.length() > 0) {
					initialCookies += "; ";
				}
				initialCookies += "PPSWSS=WSSi3";
			}
			
			// Login first
			String json = "{\"tipoDocumento\":\"" + tipodoc + "\",\"numeroDocumento\":\"" + numdoc + "\",\"password\":\"" + password + "\"}";
			
			// Login with proper session cookies
			URL urlLogin = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/login");
			HttpURLConnection connLogin = (HttpURLConnection) urlLogin.openConnection();
			connLogin.setDoOutput(true);
			connLogin.setRequestProperty("Content-Type", "application/json");
			connLogin.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connLogin.setRequestProperty("Referer", "https://www1.hospitalitaliano.org.ar/PortalWeb/");
			connLogin.setRequestProperty("Origin", "https://www1.hospitalitaliano.org.ar");
			connLogin.setRequestProperty("Accept", "application/json, text/plain, */*");
			connLogin.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en-US;q=0.8,en;q=0.7");
			if (!initialCookies.isEmpty()) {
				connLogin.setRequestProperty("Cookie", initialCookies);
			}
			connLogin.setRequestMethod("POST");
			connLogin.setConnectTimeout(30000);
			OutputStreamWriter writer = new OutputStreamWriter(connLogin.getOutputStream());
			writer.write(json);
			writer.close();
			
			StringBuffer responseLogin = new StringBuffer();
			String lineLogin;
			BufferedReader readerLogin = new BufferedReader(new InputStreamReader(connLogin.getInputStream()));
			while ((lineLogin = readerLogin.readLine()) != null) {
				responseLogin.append(lineLogin);
			}
			readerLogin.close();
			
			// Get JSESSIONID from login response
			Map<String, List<String>> loginHeaders = connLogin.getHeaderFields();
			List<String> loginCookiesHeader = loginHeaders.get("set-cookie");
			if (loginCookiesHeader == null) {
				loginCookiesHeader = loginHeaders.get("Set-Cookie");
			}
			
			if (loginCookiesHeader != null) {
				for (String cookie : loginCookiesHeader) {
					String cookieValue = cookie.split(";")[0];
					if (cookieValue.startsWith("JSESSIONID=")) {
						if (initialCookies.length() > 0) {
							initialCookies += "; ";
						}
						initialCookies += cookieValue;
						System.out.println("Added JSESSIONID from login: " + cookieValue);
						break;
					}
				}
			}
			
			JsonParser jsonparser = new JsonParser();
			JsonObject loginResponseObj = jsonparser.parse(responseLogin.toString()).getAsJsonObject();
			
			// Get the public key from login response and add PEM headers if not present
			String publicKey = loginResponseObj.get("publicKey").getAsString();
			if (!publicKey.contains("BEGIN PUBLIC KEY")) {
				publicKey = "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----";
			}
			System.out.println("Got public key from login response: " + publicKey.substring(0, 50) + "...");
			
			// Create datosLoginJIRA cookie
			String datosLoginJIRA = "{\"tipoDocumento\":\"" + tipodoc + "\",\"numeroDocumento\":\"" + numdoc + "\",\"passwordEncriptada\":\"" + loginResponseObj.get("passwordEncriptada").getAsString() + "\"}";
			String encodedDatosLoginJIRA = java.net.URLEncoder.encode(datosLoginJIRA, "UTF-8");
			
			// Combine all cookies including the session cookies and JSESSIONID
			String cookies = initialCookies + "; datosLoginJIRA=" + encodedDatosLoginJIRA;
			System.out.println("Final cookies with JSESSIONID: " + cookies);
			
			// Now make the seteosVarios call AFTER login with authentication headers (like the working request)
			try {
				URL urlSeteos = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/seteosVarios/TELEDIAGNOSTICO_ACTIVADO");
				HttpURLConnection connSeteos = (HttpURLConnection) urlSeteos.openConnection();
				connSeteos.setConnectTimeout(30000);
				connSeteos.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
				connSeteos.setRequestProperty("Accept", "application/json, text/plain, */*");
				connSeteos.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en-US;q=0.8,en;q=0.7");
				connSeteos.setRequestProperty("Cookie", cookies);
				connSeteos.setRequestProperty("x-auth-token", loginResponseObj.get("token").getAsString());
				
				// Encrypt the API path for midUtssid - match JavaScript behavior
				String seteosApiPath = "/api/auth/seteosVarios/TELEDIAGNOSTICO_ACTIVADO";
				String encryptedSeteosPath = encryptWithPublicKey(seteosApiPath, publicKey);
				connSeteos.setRequestProperty("midUtssid", encryptedSeteosPath);
				connSeteos.setRequestProperty("origen", "WEB");
				connSeteos.setRequestProperty("so", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
				connSeteos.setRequestProperty("referer", "https://www1.hospitalitaliano.org.ar/PortalWebBeta/");
				
				// Read seteos response
				BufferedReader seteosReader = new BufferedReader(new InputStreamReader(connSeteos.getInputStream()));
				String seteosResponse;
				while ((seteosResponse = seteosReader.readLine()) != null) {
					// Just consume the response
				}
				seteosReader.close();
				
				// Get JSESSIONID from seteosVarios response
				Map<String, List<String>> seteosHeaders = connSeteos.getHeaderFields();
				List<String> seteosCookiesHeader = seteosHeaders.get("set-cookie");
				if (seteosCookiesHeader == null) {
					seteosCookiesHeader = seteosHeaders.get("Set-Cookie");
				}
				
				if (seteosCookiesHeader != null) {
					for (String cookie : seteosCookiesHeader) {
						String cookieValue = cookie.split(";")[0];
						if (cookieValue.startsWith("JSESSIONID=")) {
							if (cookies.length() > 0) {
								cookies += "; ";
							}
							cookies += cookieValue;
							System.out.println("Got JSESSIONID from seteosVarios: " + cookieValue);
							break;
						}
					}
				}
				
				System.out.println("seteosVarios call completed successfully");
			} catch (Exception e) {
				System.out.println("Error calling seteosVarios: " + e.getMessage());
			}
			
			// Also call ACTIVAR_CHAT_CALL to ensure we have JSESSIONID
			try {
				URL urlChatCall = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/seteosVarios/ACTIVAR_CHAT_CALL");
				HttpURLConnection connChatCall = (HttpURLConnection) urlChatCall.openConnection();
				connChatCall.setConnectTimeout(30000);
				connChatCall.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
				connChatCall.setRequestProperty("Accept", "application/json, text/plain, */*");
				connChatCall.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en-US;q=0.8,en;q=0.7");
				connChatCall.setRequestProperty("Cookie", cookies);
				connChatCall.setRequestProperty("x-auth-token", loginResponseObj.get("token").getAsString());
				connChatCall.setRequestProperty("origen", "WEB");
				connChatCall.setRequestProperty("referer", "https://www1.hospitalitaliano.org.ar/PortalWebBeta/");
				
				// Read response
				BufferedReader chatCallReader = new BufferedReader(new InputStreamReader(connChatCall.getInputStream()));
				String chatCallResponse;
				while ((chatCallResponse = chatCallReader.readLine()) != null) {
					// Just consume the response
				}
				chatCallReader.close();
				
				// Get JSESSIONID from ACTIVAR_CHAT_CALL response
				Map<String, List<String>> chatCallHeaders = connChatCall.getHeaderFields();
				List<String> chatCallCookiesHeader = chatCallHeaders.get("set-cookie");
				if (chatCallCookiesHeader == null) {
					chatCallCookiesHeader = chatCallHeaders.get("Set-Cookie");
				}
				
				if (chatCallCookiesHeader != null) {
					for (String cookie : chatCallCookiesHeader) {
						String cookieValue = cookie.split(";")[0];
						System.out.println("ACTIVAR_CHAT_CALL cookie: " + cookieValue);
						if (cookieValue.startsWith("JSESSIONID=")) {
							// Replace or add JSESSIONID
							if (cookies.contains("JSESSIONID=")) {
								// Replace existing JSESSIONID
								cookies = cookies.replaceAll("JSESSIONID=[^;]+", cookieValue);
							} else {
								if (cookies.length() > 0) {
									cookies += "; ";
								}
								cookies += cookieValue;
							}
							System.out.println("Got JSESSIONID from ACTIVAR_CHAT_CALL: " + cookieValue);
							break;
						}
					}
				}
				
				System.out.println("ACTIVAR_CHAT_CALL call completed successfully");
			} catch (Exception e) {
				System.out.println("Error calling ACTIVAR_CHAT_CALL: " + e.getMessage());
			}
			
			System.out.println("Cookies to send: " + cookies);
			
			// Now get especialidades
			// Build the URL with properly encoded query parameters (matching JavaScript behavior)
			String especUrl = "https://www1.hospitalitaliano.org.ar/wssPortal/api/commons/especialidad";
			String especQueryString = "idUsuario=" + java.net.URLEncoder.encode(idUsuario, "UTF-8");
			URL urlEspec = new URL(especUrl + "?" + especQueryString);
			
			HttpURLConnection connEspec = (HttpURLConnection) urlEspec.openConnection();
			connEspec.addRequestProperty("x-auth-token", loginResponseObj.get("token").getAsString());
			
			// Encrypt the API path for midUtssid
			// Match JavaScript: extract from /api/ onwards with encoded query string
			String especApiPath = "/api/commons/especialidad?" + especQueryString;
			System.out.println("Encrypting especialidades path: " + especApiPath);
			String encryptedEspecPath = encryptWithPublicKey(especApiPath, publicKey);
			System.out.println("Encrypted especialidades midUtssid: " + encryptedEspecPath.substring(0, Math.min(50, encryptedEspecPath.length())) + "...");
			connEspec.addRequestProperty("midUtssid", encryptedEspecPath);
			connEspec.addRequestProperty("origen", "WEB");
			connEspec.addRequestProperty("so", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connEspec.addRequestProperty("accept", "application/json, text/plain, */*");
			connEspec.addRequestProperty("referer", "https://www1.hospitalitaliano.org.ar/PortalWebBeta/");
			connEspec.addRequestProperty("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			if (!cookies.isEmpty()) {
				connEspec.addRequestProperty("Cookie", cookies);
			}
			connEspec.setConnectTimeout(30000);
			
			BufferedReader readerEspec = new BufferedReader(new InputStreamReader(connEspec.getInputStream()));
			StringBuffer jsonEspecSB = new StringBuffer();
			String lineEspec;
			
			while ((lineEspec = readerEspec.readLine()) != null) {
				jsonEspecSB.append(lineEspec);
			}
			readerEspec.close();
			
			return Response.status(200).entity(jsonEspecSB.toString()).build();
			
		} catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": \"" + e.getLocalizedMessage() + "\"}").build();
		}
	}
	
	@GET
	@Path("/medicos")
	@Produces("application/json")
	public Response getMedicos(@QueryParam("tipoDoc") String tipodoc, @QueryParam("numeroDoc") String numdoc, @QueryParam("password") String password, @QueryParam("idUsuario") String idUsuario, @QueryParam("search") String search, @QueryParam("midUtssid") String midUtssid) {
		
		try {
			CookieManager cookieManager = new CookieManager();
			CookieHandler.setDefault(cookieManager);
			
			// Login first
			String json = "{\"tipoDocumento\":\"" + tipodoc + "\",\"numeroDocumento\":\"" + numdoc + "\",\"password\":\"" + password + "\"}";
			
			// Get initial cookie
			String rtdoHome = httpGet("https://www1.hospitalitaliano.org.ar/PortalWeb/");
			
			// Login
			URL urlLogin = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/login");
			HttpURLConnection connLogin = (HttpURLConnection) urlLogin.openConnection();
			connLogin.setDoOutput(true);
			connLogin.setRequestProperty("Content-Type", "application/json");
			connLogin.setRequestMethod("POST");
			connLogin.setConnectTimeout(30000);
			OutputStreamWriter writer = new OutputStreamWriter(connLogin.getOutputStream());
			writer.write(json);
			writer.close();
			
			StringBuffer responseLogin = new StringBuffer();
			String lineLogin;
			BufferedReader readerLogin = new BufferedReader(new InputStreamReader(connLogin.getInputStream()));
			while ((lineLogin = readerLogin.readLine()) != null) {
				responseLogin.append(lineLogin);
			}
			readerLogin.close();
			
			JsonParser jsonparser = new JsonParser();
			JsonObject loginResponseObj = jsonparser.parse(responseLogin.toString()).getAsJsonObject();
			
			// Get the public key from login response and add PEM headers if not present
			String publicKey = loginResponseObj.get("publicKey").getAsString();
			if (!publicKey.contains("BEGIN PUBLIC KEY")) {
				publicKey = "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----";
			}
			
			// Get cookies from login response
			String cookies = "";
			Map<String, List<String>> headerFields = connLogin.getHeaderFields();
			List<String> cookiesHeader = headerFields.get("Set-Cookie");
			if (cookiesHeader != null) {
				for (String cookie : cookiesHeader) {
					if (cookies.length() > 0) {
						cookies += "; ";
					}
					cookies += cookie.split(";")[0]; // Get only the name=value part
				}
			}
			
			// Now get medicos - build URL with proper encoding
			String medicosQueryString = "todos=" + java.net.URLEncoder.encode("true", "UTF-8") +
				"&search=" + java.net.URLEncoder.encode(search, "UTF-8");
			URL urlMedicos = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/commons/medico/" + idUsuario + "?" + medicosQueryString);
			HttpURLConnection connMedicos = (HttpURLConnection) urlMedicos.openConnection();
			connMedicos.addRequestProperty("x-auth-token", loginResponseObj.get("perfil").getAsJsonObject().get("token").getAsString());
			
			// Encrypt the API path for midUtssid - match JavaScript behavior
			String medicosApiPath = "/api/commons/medico/" + idUsuario + "?" + medicosQueryString;
			String encryptedMedicosPath = encryptWithPublicKey(medicosApiPath, publicKey);
			connMedicos.addRequestProperty("midUtssid", encryptedMedicosPath);
			connMedicos.addRequestProperty("origen", "WEB");
			connMedicos.addRequestProperty("so", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connMedicos.addRequestProperty("accept", "application/json, text/plain, */*");
			connMedicos.addRequestProperty("referer", "https://www1.hospitalitaliano.org.ar/PortalWebBeta/");
			if (!cookies.isEmpty()) {
				connMedicos.addRequestProperty("Cookie", cookies);
			}
			connMedicos.setConnectTimeout(30000);
			
			BufferedReader readerMedicos = new BufferedReader(new InputStreamReader(connMedicos.getInputStream()));
			StringBuffer jsonMedicosSB = new StringBuffer();
			String lineMedicos;
			
			while ((lineMedicos = readerMedicos.readLine()) != null) {
				jsonMedicosSB.append(lineMedicos);
			}
			readerMedicos.close();
			
			return Response.status(200).entity(jsonMedicosSB.toString()).build();
			
		} catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": \"" + e.getLocalizedMessage() + "\"}").build();
		}
	}
	
	@GET
	@Path("/lugares-atencion-medico")
	@Produces("application/json")
	public Response getLugaresAtencionMedico(@QueryParam("tipoDoc") String tipodoc, @QueryParam("numeroDoc") String numdoc, @QueryParam("password") String password, @QueryParam("idUsuario") String idUsuario, @QueryParam("medicoId") String medicoId) {
		
		try {
			CookieManager cookieManager = new CookieManager();
			CookieHandler.setDefault(cookieManager);
			
			// Login first
			String json = "{\"tipoDocumento\":\"" + tipodoc + "\",\"numeroDocumento\":\"" + numdoc + "\",\"password\":\"" + password + "\"}";
			
			// Get initial cookie
			String rtdoHome = httpGet("https://www1.hospitalitaliano.org.ar/PortalWeb/");
			
			// Login
			URL urlLogin = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/login");
			HttpURLConnection connLogin = (HttpURLConnection) urlLogin.openConnection();
			connLogin.setDoOutput(true);
			connLogin.setRequestProperty("Content-Type", "application/json");
			connLogin.setRequestMethod("POST");
			connLogin.setConnectTimeout(30000);
			OutputStreamWriter writer = new OutputStreamWriter(connLogin.getOutputStream());
			writer.write(json);
			writer.close();
			
			StringBuffer responseLogin = new StringBuffer();
			String lineLogin;
			BufferedReader readerLogin = new BufferedReader(new InputStreamReader(connLogin.getInputStream()));
			while ((lineLogin = readerLogin.readLine()) != null) {
				responseLogin.append(lineLogin);
			}
			readerLogin.close();
			
			JsonParser jsonparser = new JsonParser();
			JsonObject loginResponseObj = jsonparser.parse(responseLogin.toString()).getAsJsonObject();
			
			// Get the public key from login response and add PEM headers if not present
			String publicKey = loginResponseObj.get("publicKey").getAsString();
			if (!publicKey.contains("BEGIN PUBLIC KEY")) {
				publicKey = "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----";
			}
			
			// Get cookies from login response
			String cookies = "";
			Map<String, List<String>> headerFields = connLogin.getHeaderFields();
			List<String> cookiesHeader = headerFields.get("Set-Cookie");
			if (cookiesHeader != null) {
				for (String cookie : cookiesHeader) {
					if (cookies.length() > 0) {
						cookies += "; ";
					}
					cookies += cookie.split(";")[0]; // Get only the name=value part
				}
			}
			
			// Now get lugares de atencion - build URL with proper encoding
			String lugaresQueryString = "esMiMedico=" + java.net.URLEncoder.encode("false", "UTF-8") +
				"&idUsuario=" + java.net.URLEncoder.encode(idUsuario, "UTF-8");
			URL urlLugares = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/commons/lugar-atencion/medico/" + medicoId + "?" + lugaresQueryString);
			HttpURLConnection connLugares = (HttpURLConnection) urlLugares.openConnection();
			connLugares.addRequestProperty("x-auth-token", loginResponseObj.get("perfil").getAsJsonObject().get("token").getAsString());
			
			// Encrypt the API path for midUtssid - match JavaScript behavior
			String lugaresApiPath = "/api/commons/lugar-atencion/medico/" + medicoId + "?" + lugaresQueryString;
			String encryptedLugaresPath = encryptWithPublicKey(lugaresApiPath, publicKey);
			connLugares.addRequestProperty("midUtssid", encryptedLugaresPath);
			connLugares.addRequestProperty("origen", "WEB");
			connLugares.addRequestProperty("so", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connLugares.addRequestProperty("accept", "application/json, text/plain, */*");
			connLugares.addRequestProperty("referer", "https://www1.hospitalitaliano.org.ar/PortalWebBeta/");
			if (!cookies.isEmpty()) {
				connLugares.addRequestProperty("Cookie", cookies);
			}
			connLugares.setConnectTimeout(30000);
			
			BufferedReader readerLugares = new BufferedReader(new InputStreamReader(connLugares.getInputStream()));
			StringBuffer jsonLugaresSB = new StringBuffer();
			String lineLugares;
			
			while ((lineLugares = readerLugares.readLine()) != null) {
				jsonLugaresSB.append(lineLugares);
			}
			readerLugares.close();
			
			return Response.status(200).entity(jsonLugaresSB.toString()).build();
			
		} catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": \"" + e.getLocalizedMessage() + "\"}").build();
		}
	}
	
	@GET
	@Path("/lugares-atencion-especialidad")
	@Produces("application/json")
	public Response getLugaresAtencionEspecialidad(@QueryParam("tipoDoc") String tipodoc, @QueryParam("numeroDoc") String numdoc, @QueryParam("password") String password, @QueryParam("idUsuario") String idUsuario, @QueryParam("especialidadId") String especialidadId) {
		
		try {
			CookieManager cookieManager = new CookieManager();
			CookieHandler.setDefault(cookieManager);
			
			// Login first
			String json = "{\"tipoDocumento\":\"" + tipodoc + "\",\"numeroDocumento\":\"" + numdoc + "\",\"password\":\"" + password + "\"}";
			
			// Get initial cookie
			String rtdoHome = httpGet("https://www1.hospitalitaliano.org.ar/PortalWeb/");
			
			// Login
			URL urlLogin = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/auth/login");
			HttpURLConnection connLogin = (HttpURLConnection) urlLogin.openConnection();
			connLogin.setDoOutput(true);
			connLogin.setRequestProperty("Content-Type", "application/json");
			connLogin.setRequestMethod("POST");
			connLogin.setConnectTimeout(30000);
			OutputStreamWriter writer = new OutputStreamWriter(connLogin.getOutputStream());
			writer.write(json);
			writer.close();
			
			StringBuffer responseLogin = new StringBuffer();
			String lineLogin;
			BufferedReader readerLogin = new BufferedReader(new InputStreamReader(connLogin.getInputStream()));
			while ((lineLogin = readerLogin.readLine()) != null) {
				responseLogin.append(lineLogin);
			}
			readerLogin.close();
			
			JsonParser jsonparser = new JsonParser();
			JsonObject loginResponseObj = jsonparser.parse(responseLogin.toString()).getAsJsonObject();
			
			// Get the public key from login response
			String publicKey = loginResponseObj.get("publicKey").getAsString();
			
			// Now get lugares de atencion por especialidad - build URL with proper encoding
			String lugaresEspecQueryString = "idUsuario=" + java.net.URLEncoder.encode(idUsuario, "UTF-8");
			URL urlLugares = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/commons/lugar-atencion/especialidad/" + especialidadId + "?" + lugaresEspecQueryString);
			HttpURLConnection connLugares = (HttpURLConnection) urlLugares.openConnection();
			connLugares.addRequestProperty("x-auth-token", loginResponseObj.get("perfil").getAsJsonObject().get("token").getAsString());
			
			// Encrypt the API path for midUtssid - match JavaScript behavior
			String lugaresEspecApiPath = "/api/commons/lugar-atencion/especialidad/" + especialidadId + "?" + lugaresEspecQueryString;
			String encryptedLugaresEspecPath = encryptWithPublicKey(lugaresEspecApiPath, publicKey);
			connLugares.addRequestProperty("midUtssid", encryptedLugaresEspecPath);
			connLugares.addRequestProperty("origen", "WEB");
			connLugares.addRequestProperty("so", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
			connLugares.addRequestProperty("accept", "application/json, text/plain, */*");
			connLugares.addRequestProperty("referer", "https://www1.hospitalitaliano.org.ar/PortalWebBeta/");
			connLugares.setConnectTimeout(30000);
			
			BufferedReader readerLugares = new BufferedReader(new InputStreamReader(connLugares.getInputStream()));
			StringBuffer jsonLugaresSB = new StringBuffer();
			String lineLugares;
			
			while ((lineLugares = readerLugares.readLine()) != null) {
				jsonLugaresSB.append(lineLugares);
			}
			readerLugares.close();
			
			return Response.status(200).entity(jsonLugaresSB.toString()).build();
			
		} catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": \"" + e.getLocalizedMessage() + "\"}").build();
		}
	}
	
	public static JsonArray convertToJSON(Entity usuario, List<Entity> listaAlertas)
            throws Exception {
        	 JsonArray jsonArray = new JsonArray();
             Gson gson = new GsonBuilder().create();
             Iterator<Entity> iter = listaAlertas.iterator();
             while(iter.hasNext()) {
            	 JsonObject obj = new JsonObject();
            	 Entity e = iter.next();
            	 for(Map.Entry<String, Object> prop : e.getProperties().entrySet()) {
            		 obj.add(prop.getKey(), gson.toJsonTree(prop.getValue()));
            	 }
            	 obj.add("alertaid",gson.toJsonTree(e.getKey().getId()));
            	 obj.add("mail", gson.toJsonTree(usuario.getProperty("mail")));
            	 obj.add("tipodoc", gson.toJsonTree(usuario.getProperty("tipoDocumento")));
            	 obj.add("numerodoc", gson.toJsonTree(usuario.getProperty("numeroDocumento")));
            	 
            	 jsonArray.add(obj);
            	
             }
             
        return jsonArray;
    }
	
	public String httpGet(String urlStr) {
		BufferedReader reader = null;
		StringBuffer sb = null;
		try {
			URL url = new URL(urlStr);
			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setConnectTimeout(30000);
			reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
			 sb = new StringBuffer();
		    String line;

		    while ((line = reader.readLine()) != null) {
		      sb.append(line);
		    }
		    reader.close();
		    
		} catch (IOException e) {
			e.printStackTrace();
		}
		return sb.toString();
	}
	
	private String encryptWithPublicKey(String data, String publicKeyPem) throws Exception {
		// Remove PEM headers and decode base64
		String publicKeyContent = publicKeyPem
			.replace("-----BEGIN PUBLIC KEY-----", "")
			.replace("-----END PUBLIC KEY-----", "")
			.replaceAll("\\s", "");
		
		byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);
		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
		java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
		PublicKey publicKey = keyFactory.generatePublic(keySpec);
		
		// Encrypt using RSA-OAEP with SHA-256 for both main hash and MGF1 (matching Web Crypto API with SHA-256)
		Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
		java.security.spec.MGF1ParameterSpec mgf1Spec = new java.security.spec.MGF1ParameterSpec("SHA-256");
		javax.crypto.spec.OAEPParameterSpec oaepParams = new javax.crypto.spec.OAEPParameterSpec(
			"SHA-256", "MGF1", mgf1Spec, javax.crypto.spec.PSource.PSpecified.DEFAULT);
		cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams);
		
		byte[] encryptedData = cipher.doFinal(data.getBytes("UTF-8"));
		
		// Return base64 encoded result
		return Base64.getEncoder().encodeToString(encryptedData);
	}

}
