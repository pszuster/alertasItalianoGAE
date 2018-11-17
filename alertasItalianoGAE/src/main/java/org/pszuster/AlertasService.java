package org.pszuster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.Logger;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.HttpsURLConnection;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import org.apache.http.entity.StringEntity;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.KeyFactory;
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
				
				String lugaresAtencionStr="";
				List<String> lugaresAtencion = Arrays.asList(alerta.getProperty("lugares").toString().split(","));
				for (String lugar: lugaresAtencion) {
					lugaresAtencionStr+="&lugarAtencionIds=" + lugar;
				}
				//Busco primeros 10 turnos							
				
				URL urlTurnos = new URL("https://www1.hospitalitaliano.org.ar/wssPortal/api/turnos/reserva/buscar?esMiMedico=false&especialidadId=" + alerta.getProperty("especialidad") + "&idPersonaFederada=" + loginResponseObj.get("id") + "&limit=10" + lugaresAtencionStr + "&pageInit=0");
	    		HttpURLConnection connTurnos = (HttpURLConnection) urlTurnos.openConnection();
	    		connTurnos.addRequestProperty("x-auth-token", loginResponseObj.get("perfil").getAsJsonObject().get("token").getAsString());
	    		connTurnos.setConnectTimeout(30000);
	    		BufferedReader readerTurnos = new BufferedReader(new InputStreamReader(connTurnos.getInputStream()));
	    	    StringBuffer jsonTurnosSB = new StringBuffer();
	    	    String lineTurnos;

	    	    while ((lineTurnos = readerTurnos.readLine()) != null) {
	    	    	jsonTurnosSB.append(lineTurnos);
	    	    }
	    	    readerTurnos.close();
	    	    String turnosStr = jsonTurnosSB.toString();			
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
		     alerta.setProperty("especialidad", datosAlerta.get("especialidad").getAsInt());
		     alerta.setProperty("lugares", lugares);
		     alerta.setProperty("lastfound", "");
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
			 datastore.delete(KeyFactory.createKey(KeyFactory.createKey("Usuario",numeroDoc),"Alerta", alertaID));
		 }catch (Exception e) {
			e.printStackTrace();
			return Response.status(500).entity("{\"error\": '"+ e.getLocalizedMessage() + "'}").build();
		}
		return Response.status(200).entity("{\"status\": \"OK\"}").build();
		
	}
	
	
	
	
	
	
	
	
	
	public static JsonArray convertToJSON(Entity usuario, List<Entity> listaAlertas)
            throws Exception {
        	 JsonArray jsonArray = new JsonArray();
             Gson gson = new GsonBuilder().create();
             JsonObject obj = new JsonObject();
             Iterator<Entity> iter = listaAlertas.iterator();
             while(iter.hasNext()) {
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

}
