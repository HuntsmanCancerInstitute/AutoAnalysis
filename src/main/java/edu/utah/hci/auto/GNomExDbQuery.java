package edu.utah.hci.auto;

import java.sql.*;
import java.util.ArrayList;

public class GNomExDbQuery {
	
	// jdbc:sqlserver://hci-db.hci.utah.edu:1433;databaseName=gnomex;user=pipeline;password=xxxxxxx
	// replace xxxxx pwd from https://ri-confluence.hci.utah.edu/pages/viewpage.action?pageId=38076459
	private String connectionUrl = null;
	
	//internal
	private Connection con = null;
	private Statement stmt = null;
	private ResultSet rs = null;
	private GNomExRequest[] requests = null;
	private GNomExSample[] samples = null;
	private boolean failed = false;
	private boolean verbose = true;

	public GNomExDbQuery (String connectionUrl, boolean verbose) {
		this.connectionUrl = connectionUrl;
		this.verbose = verbose;
		
		try {
			runQueries();
		} catch (Exception e) {
			failed = true;
			e.printStackTrace();
			
		} finally {
			if(rs != null) try { rs.close(); } catch(Exception e) {}
			if(stmt != null) try { stmt.close(); } catch(Exception e) {}
			if(con != null) try { con.close(); } catch(Exception e) {}
		}
	}
	
	private void parseRequests(ArrayList<String[]> requestsAl) {
		requests = new GNomExRequest[requestsAl.size()];
		int num = requestsAl.size();
		for (int i=0; i< num; i++) requests[i] = new GNomExRequest(requestsAl.get(i));
	}
	
	public void runQueries() throws Exception {
		if (verbose) Util.pl("Instantiating a driver...");
		Driver d = (Driver) Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver").newInstance();
		if (verbose) Util.pl("\tDriver "+d);

		//establish connection
		if (verbose) Util.pl("Attempting to make a connection...");
		Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
		con = DriverManager.getConnection(connectionUrl);
		
		//query last 12 months, fastq is only guaranteed to be around for 3 months
		if (verbose) Util.pl("Attempting query for AutoAnalysis....");
		runAutoAnalysisQuery(d, con);
		
		if (verbose) Util.pl("Attempting query for Species Demux....");
		runSpeciesQuery(d, con);
}
	private void runSpeciesQuery(Driver d, Connection con) throws Exception{
		
		String SQL = "SELECT DISTINCT request.number, sample.number, organism.organism, request.createDate, request.lastModifyDate, request.oraCompression "+
		"FROM request "+
		"join project on project.idproject = request.idproject "+
		"join sample on sample.idrequest = request.idrequest "+
		"join organism on sample.idorganism = organism.idorganism "+
		"WHERE (request.lastModifyDate > (select dateadd(month, -12, getdate()))) "+
		"OR (request.createDate > (select dateadd(month, -12, getdate()))) "+
		"ORDER BY request.lastModifyDate;";

		int numReturnValues = 6;
		
		stmt = con.createStatement();
		rs = stmt.executeQuery(SQL);
		if (verbose) Util.pl("Loading results...");
		ArrayList<GNomExSample> requestsAl = new ArrayList<GNomExSample>();
		while (rs.next()) {
			String[] results = new String[numReturnValues];
			int resultsIndex = 0;
			for (int i=1; i<numReturnValues+1; i++) {
				String val = rs.getString(i);
				if (val != null) results[resultsIndex++] = val.trim();
				else results[resultsIndex++] = "NA";
			}
			GNomExSample gs = new GNomExSample(results);
			requestsAl.add(gs);
//Util.pl(gs.toString());
//Util.pl(Util.stringArrayToString(results, "\t"));
		}
//Util.pl(requestsAl.size()+"\tNumSpeciesRes");

		samples = new GNomExSample[requestsAl.size()];
		requestsAl.toArray(samples);
		
	}
	
	private void runAutoAnalysisQuery(Driver d, Connection con) throws Exception{
		String SQL = "SELECT DISTINCT "+
				"request.number,  "+				//0
				"request.createDate,  "+			//1
				"appuser.email, "+					//2
				"lab.lastname,  "+					//3
				"lab.firstname,  "+					//4
				"organism.organism, "+				//5
				"application.application, "+		//6
				"request.analysisInstructions, "+	//7   NA or freeform txt
				"request.alignToGenomeBuild, "+     //8  NA, N, or Y
				"request.bioInformaticsAssist, "+   //9  N or Y
				"request.codeRequestStatus, "+      //10
				"request.lastModifyDate "+			//11
				"FROM request  "+
				"join project on project.idproject = request.idproject  "+
				"join lab on lab.idlab = request.idlab  "+
				"join sample on sample.idrequest = request.idrequest "+
				"join organism on sample.idorganism = organism.idorganism "+
				"join appuser on appuser.idappuser = request.idappuser  "+
				"join application on application.codeapplication = request.codeapplication "+
				"WHERE (request.lastModifyDate > (select dateadd(month, -12, getdate())) "+
				"OR request.createDate > (select dateadd(month, -12, getdate()))) "+
				"AND (request.bioInformaticsAssist = 'Y' OR request.alignToGenomeBuild = 'Y') "+
				"ORDER BY request.lastModifyDate; ";
		
		
//new
// "WHERE (request.lastModifyDate > (select dateadd(month, -12, getdate())) "+
// "OR request.createDate > (select dateadd(month, -12, getdate()))) "+
		
//old
//	"WHERE request.createDate > (select dateadd(month, -12, getdate())) "+
//	"AND (request.bioInformaticsAssist = 'Y' OR request.alignToGenomeBuild = 'Y') "+
		
		int numReturnValues = 12; 
		
		stmt = con.createStatement();
		rs = stmt.executeQuery(SQL);
		if (verbose) Util.pl("Loading results...");
		ArrayList<String[]> requestsAl = new ArrayList<String[]>();
		while (rs.next()) {
			String[] results = new String[numReturnValues];
			int resultsIndex = 0;
			for (int i=1; i<numReturnValues+1; i++) {
				String val = rs.getString(i);
				if (val != null) results[resultsIndex++] = val.trim();
				else results[resultsIndex++] = "NA";
			}
			requestsAl.add(results);
//Util.pl(Util.stringArrayToString(results, "\n")+"\n");
		}
//Util.pl("NumMainAAReq: "+requestsAl.size());
		parseRequests(requestsAl);
		
	}

	//not used, delete
	private void runAutoAnalysisQueryOld(Driver d, Connection con) throws Exception{
		String SQL = "SELECT DISTINCT "+
				"request.number,  "+				//0
				"request.lastModifyDate,  "+			//1
				"appuser.email, "+					//2
				"lab.lastname,  "+					//3
				"lab.firstname,  "+					//4
				"organism.organism, "+				//5
				"application.application, "+		//7
				"request.analysisInstructions, "+	//8   NA or freeform txt
				"request.alignToGenomeBuild, "+     //9  NA, N, or Y
				"request.bioInformaticsAssist, "+   //10  N or Y
				"request.codeRequestStatus "+       //11
				"FROM request  "+
				"join project on project.idproject = request.idproject  "+
				"join lab on lab.idlab = request.idlab  "+
				"join sample on sample.idrequest = request.idrequest "+
				"join organism on sample.idorganism = organism.idorganism "+
				"join appuser on appuser.idappuser = request.idappuser  "+
				"join application on application.codeapplication = request.codeapplication "+
				"WHERE request.lastModifyDate > (select dateadd(month, -12, getdate())) "+
				"AND (request.bioInformaticsAssist = 'Y' OR request.alignToGenomeBuild = 'Y') "+
				"ORDER BY request.lastModifyDate; ";
		//"AND request.codeRequestStatus = 'COMPLETE' "+
		
		int numReturnValues = 11;
		
		stmt = con.createStatement();
		rs = stmt.executeQuery(SQL);
		if (verbose) Util.pl("Loading results...");
		ArrayList<String[]> requestsAl = new ArrayList<String[]>();
		while (rs.next()) {
			String[] results = new String[numReturnValues];
			int resultsIndex = 0;
			for (int i=1; i<numReturnValues+1; i++) {
				String val = rs.getString(i);
				if (val != null) results[resultsIndex++] = val.trim();
				else results[resultsIndex++] = "NA";
			}
			requestsAl.add(results);
			//Util.pl(Util.stringArrayToString(results, "\n")+"\n");
		}

		parseRequests(requestsAl);
		
	}

	public static void main (String[] args) {
		//replace xxxxx pwd from https://ri-confluence.hci.utah.edu/pages/viewpage.action?pageId=38076459
		String connectionUrl = "jdbc:sqlserver://hci-db.hci.utah.edu:1433;databaseName=gnomex;user=pipeline;password=XXXXX;encrypt=true;trustServerCertificate=true";
		new GNomExDbQuery(connectionUrl, true);
	}

	public GNomExRequest[] getRequests() {
		return requests;
	}

	public boolean isFailed() {
		return failed;
	}

	public GNomExSample[] getSamples() {
		return samples;
	}

}



