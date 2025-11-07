package edu.utah.hci.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**Sets up primary AutoAnalysis folders for GNomEx Experiment Requests
 * 1) Interrogates the standard GNomEx sql server for analysis requests submitted with the Experiment Request
 * 2) Looks in the GNomEx repo to see which have been completed, are in process, or have failed
 * 3) Runs MultiQC on those that are all complete
 * 7) Emails clients regarding the job status.
 * Run on hci-deadhorse.hci.utah.edu
 * */
public class GNomExAutoAnalysis {

	//config fields
	private File configFile = null;
	private File credentialFile = null;
	private File supportedOrgLibWfConfigFile = null;
	private String connectionUrl = null;
	private HashMap<String, File> experimentalSubDirs = null;
	private String adminEmail = null;
	private String analysisReadyEmail = null;
	private String hciLinkDirectoryString = null;
	private File hciLinkDirectory = null;
	private File hciTempDirectory = null;
	private boolean verbose = false;
	private double hoursToWait = 0;
	private long waitTime = 0;
	private File useqJobCleaner = null;
	private File useqAggregateQCStats2 = null;
	private File builtJiraTickets = null;
	private String experimentLinkUrl = null;
	private String jiraUrl = null;
	private String jiraApiUrl = null;
	private String dataPolicyUrl = null;
	private String experimentRequestsToProc = null;
	private String jiraApiCredentials = null;
	private File sampleSpeciesFile = null;
	
	//internal fields
	//Date formatting, 2023-11-14 07:43:13.38
	private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	private Calendar calendar = Calendar.getInstance();
	private int numberRetriesForMultiQC = 2;
	private HashMap<String, String[]> orgLibWorkflowDocs = null;
	private double hoursPassed = 0;
	private TreeSet<String> jobsProcessed = new TreeSet<String>();
	private TreeMap<String, Integer> missingOrgLibPrep = new TreeMap<String, Integer>();
	private String dnaAlignQCWFName = "dnaAlignQC";
	public static int minimumFastqFileLineCount = 4000;
	private HashSet<String> idsWithJiraTickets = null;
	private OraSpeciesMatcher oraSpeciesMatcher = null;
	
	//Requests split by status
	private ArrayList<GNomExRequest> grsToBuildAutoAnalysis = new ArrayList<GNomExRequest>();
	private ArrayList<GNomExRequest> grsWithAutoAnalysis = new ArrayList<GNomExRequest>();
	private ArrayList<GNomExRequest> grsSkipped = new ArrayList<GNomExRequest>();
	private ArrayList<GNomExRequest> grsRequestingAnalysisAssistance = new ArrayList<GNomExRequest>();
	private ArrayList<GNomExRequest> grsToMultiQC = new ArrayList<GNomExRequest>();
	private ArrayList<String> errorMessages = new ArrayList<String>();

	public GNomExAutoAnalysis (String[] args) {
		try {

			processArgs(args);

			while (true) {
				Util.pl("\n########### "+ Util.getDateTime()+ " ###########");
				
				// Query the GNomEx DB for experiment requests
				Util.pl("\nChecking the GNomEx db...");
				GNomExDbQuery dbQuery = new GNomExDbQuery(connectionUrl, verbose);
				if (dbQuery.isFailed()) throw new Exception("ERROR with querying the GNomEx DB");
				
				// Save sample species for demux ORA compression
				parseSampleSpecies(dbQuery.getSamples());

				// Find new requests ready for analysis, find existing analysis jobs and check their status
				parseRequests(dbQuery.getRequests());

				// Build new AutoAnalysis Jobs
				buildAutoAnalysisJobs();

				// Check the existing AutoAnalysis (AutoAnalysis/22597R_27Dec2023) and it's sub job directories (AutoAnalysis/22597R_27Dec2023/22597X4)
				checkExistingAutoAnalysis();

				// Run MultiQC and delete the symlinked AutoAnalysis jobs
				runMultiQCEmailClients();
				
				createJiraTickets();

				// Loop or exit?
				if (hoursToWait == 0) return;
				Util.pl("Sleeping "+hoursToWait+" hrs ...");
				Thread.sleep(waitTime);
				
				clearPriorArrays();
				
				emailAlive();

			}

		} catch (Exception e) {
			emailErrorMessage("FATAL: GNomExAutoAnalysis terminated, daemon offline! Check HCI run log.\n", e);
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	private void parseSampleSpecies(GNomExSample[] samples) throws Exception{
		Util.pl("Parsing Sample Species for ORA...");
		
		//set ora species in samples
		String currGSpecies = "";
		String currOSpecies = "";
		for (GNomExSample s: samples) {
			if (s.getSpecies().equals(currGSpecies) == false) {
				currGSpecies = s.getSpecies();
				currOSpecies = oraSpeciesMatcher.fetchOraSpecies(currGSpecies);
			}
			s.setOraSpecies(currOSpecies);
		}
		
		//write out new tmp sample file
		File tmpSamples = new File(sampleSpeciesFile.getParent(), "tmp_"+sampleSpeciesFile.getName());
		PrintWriter out = new PrintWriter( new FileWriter(tmpSamples));
		out.println("#ExperimentID\tSampleID\tSpecies\tOraCompress\tOraCompressionSpecies\tLastModifiedDate");
		for (GNomExSample s: samples) out.println(s.toString());
		out.close();
		//copy it to the real location
		if (tmpSamples.renameTo(sampleSpeciesFile) == false) {
			throw new Exception("Error: failed to copy tmp sample species file "+tmpSamples+" to "+sampleSpeciesFile);
		}
		sampleSpeciesFile.setReadable(true);
	}

	private void createJiraTickets() throws Exception{
		Util.pl("Creating jira help request tickets...");
		if (grsRequestingAnalysisAssistance.size()==0) return;
		
		//load prior made tickets
		if (idsWithJiraTickets == null) idsWithJiraTickets = Util.loadFileIntoHashSet(builtJiraTickets);
		HashSet<String> newIds = new HashSet<String>();
		ArrayList<GNomExRequest> reqToTicket = new ArrayList<GNomExRequest>();
		
		//for each request see if it is new
		for (GNomExRequest gr: grsRequestingAnalysisAssistance) {
			String id = gr.getOriginalRequestId();
			if (idsWithJiraTickets.contains(id) == false) {
				newIds.add(id);
				reqToTicket.add(gr);
			}
		}
		
		//combine and save the new ids overwriting the original file, use an intermediate
		if (newIds.size()!=0) {
			Util.pl("\tMaking "+newIds.size()+" jira tickets");
			idsWithJiraTickets.addAll(newIds);
			File temp = new File (builtJiraTickets.getCanonicalPath()+".tmp");
			if (Util.writeHashSet(idsWithJiraTickets, temp) == false) throw new IOException("Failed to write the updated jira ticket id file. "+temp);
			else if (temp.renameTo(builtJiraTickets) == false) throw new IOException("Failed to rename the updated jira ticket id file. "+temp);
			
			//make the tickets
			for (GNomExRequest gr: reqToTicket) {
				// write the json data file
				File json = new File (hciTempDirectory, gr.getOriginalRequestId()+".tmp.json");
				String data = gr.getJiraTicketData();
				Util.writeString(data, json);
				
				// execute the curl cmd
				String[] cmd = {"curl", "-D-", "-u", jiraApiCredentials, "-X", "POST", 
						"--data", "@"+json.getCanonicalPath(), "-H", 
						"Content-Type: application/json", jiraApiUrl};
				

				String[] results = Util.executeCommandLine(cmd);
				
				//check to see that a ticket was created
				boolean ok = false;
				for (String s: results) {
					if (s.contains("BSD-") && s.contains("self")) {
						ok = true;
						if (verbose) Util.pl("\t"+ gr.getOriginalRequestId()+"\t"+s);
						break;
					}
				}
				if (ok == false) throw new IOException ("Failed to create a jira help ticket for "+data);
				json.delete();
			}
		}
	}

	/*Sends an email that service is alive every 24hrs*/
	private void emailAlive() {
		hoursPassed += hoursToWait;
		if (hoursPassed >= 24) {
			hoursPassed = 0;
			Util.pl("Emailing admin that daemon is running...");
			String subject = "GNomEx AutoAnalysis is alive "+Util.getDateTime();
			String body = "\n"+jobsProcessed.size()+" jobs processed in the last 24hrs\n";
			if (jobsProcessed.size()!=0) body = body + jobsProcessed;
			Util.sendMuttEmail(subject, adminEmail, body);
			jobsProcessed.clear();
		}
		
	}

	private void clearPriorArrays() {
		grsToBuildAutoAnalysis.clear();
		grsWithAutoAnalysis.clear();
		grsSkipped.clear();
		grsRequestingAnalysisAssistance.clear();
		grsToMultiQC.clear();
		errorMessages.clear();
	}

	private void emailErrorMessage(String error, Exception e) {
		Util.pl("Emailing error messages...");
		String subject = "GNomExAutoAnalysis ERROR";
		String body = error+"\n"+e.toString();
		Util.sendMuttEmail(subject, adminEmail, body);
	}
	
	private void runMultiQCEmailClients() throws IOException {
		// Any jobs?
		if (grsToMultiQC.size() ==0) return;
		
		
		Util.pl("\nRunning MultiQC and JobCleaner...");
		for (GNomExRequest gr: grsToMultiQC) {
			
			// AutoAnalysis_22Dec2023
			if (verbose) Util.pl("\t"+gr.getAutoAnalysisMainDirectory());
			String alignDir = gr.getAutoAnalysisMainDirectory().getCanonicalPath();
			String jobsDir = gr.getAutoAnalysisJobsDirectory().getCanonicalPath();
			String name = gr.getRequestIdCleaned();
			
			// See if there are any additional multiQC options
			String orgLib = gr.getOrganism()+"_"+Util.WHITE_SPACE.matcher(gr.getLibraryPreparation()).replaceAll("");
			
			String opts = "";
			String[] f = orgLibWorkflowDocs.get(orgLib);
			if (f==null) Util.pl ("WARNING: failed to fetch library info for multiQC for '"+orgLib+"'");
			else opts = f[1].trim();
			
			if (opts.toLowerCase().equals("none")) opts = "";
			
			// Current working directory, needed for multi qc config files if present
			String workingDir = new File( System.getProperty("user.dir")).getCanonicalPath();
			
			StringBuilder sb = new StringBuilder();
			// set to exit upon fail
			sb.append("set -e\n");
			// docker multiqc
			sb.append("docker run --rm --user $(id -u):$(id -g) -v "+alignDir+":"+alignDir+" -v "+workingDir+":"+workingDir+
					" ewels/multiqc multiqc "+opts+" --outdir "+alignDir+"/MultiQC --title "+name+
					" --filename "+name+"_MultiQCReport.html "+jobsDir+"\n");

			// CellRanger ATAC is not supported by the cellranger module so don't use it!
			
			// dnaAlign?
			// fetch all of the sing files these will contain the name of the workflow, e.g. dnaAlignQC.sing
			ArrayList<File> singFiles = Util.fetchAllFilesRecursively(new File(jobsDir), ".sing");
			if (singFiles.size()==0) throw new IOException("ERROR: failed to fetch any xxx.sing files from "+jobsDir);
			if (singFiles.get(0).getName().startsWith("dnaAlignQC")) {
				sb.append("java -jar -Xmx5G "+useqAggregateQCStats2.getCanonicalPath()+" -j "+
						jobsDir+" -s "+alignDir+"/AggQC -a '.+CutAdapt.log' -r '.+UniObRC.json.gz' \n");
			}
			
			// small file cleanup
			sb.append("java -jar -Xmx5G "+useqJobCleaner.getCanonicalPath()+" -n 'Logs,RunScripts' -m -r "+
					jobsDir+" -e 'COMPLETE,.tbi,.crai,.bai'\n");
			
			//run it, will retry
			CommandRunner cr = new CommandRunner (numberRetriesForMultiQC, verbose, gr.getAutoAnalysisMainDirectory(), new String[] {sb.toString()});
			if (cr.isFailed()) throw new IOException(cr.getErrorMessage());
			
			//email client that data is ready
			emailClient(gr);
		}
	}

	private void emailClient(GNomExRequest gr) {
		String subject = "GNomEx AutoAnalysis for "+gr.getRequestIdCleaned()+" is complete";
		
		StringBuilder sb = new StringBuilder();
		sb.append("AutoAnalysis and MultiQC have completed for the samples in "+gr.getRequestIdCleaned()+ "\n");
		sb.append("\tOrgLib: "+gr.getOrganism()+" : "+gr.getLibraryPreparation()+"\n");
		sb.append("\tLabGroup: "+gr.getLabGroupLastName()+", "+gr.getLabGroupFirstName()+"\n");
		sb.append("\tRequestor: "+gr.getRequestorEmail()+ "\n\n");
		sb.append("Access these via:\n\t"+experimentLinkUrl+ gr.getOriginalRequestId()+ "\n\tand look in the  '"+gr.getAutoAnalysisMainDirectory().getName()+"'  directory.\n\n");
		sb.append("If you would like additional analysis assistance, submit a help request through our Jira ticketing system:\n\t"+ jiraUrl+"\n\n");
		sb.append("Lastly, remember that all of the data files in this Experiment Request will be deleted after three months. If your group has a CORE "
				+ "Browser configured AWS account (https://hci-apps-ext.hci.utah.edu/core-browser), the files will be uploaded into it and then deleted. See:\n\t"+ dataPolicyUrl+ "\n\n");
		sb.append("HCI Cancer Bioinformatics Shared Resource (CBI)\nhttps://huntsmancancer.org/cbi\n\n");

		Util.sendMuttEmail(subject, gr.getRequestorEmail()+","+analysisReadyEmail, sb.toString());
		
	}

	private void checkExistingAutoAnalysis() throws Exception{
		// Any jobs?
		if (grsWithAutoAnalysis.size() ==0) return;

		Util.pl("\nChecking AutoAnalysis jobs...");
		for (GNomExRequest gr: grsWithAutoAnalysis) {

			File oldAADir = gr.getAutoAnalysisMainDirectory();
			
			//any Fastq? Might be archived
			boolean fastqOK = gr.checkFastq();
			if (fastqOK == false) {
				if (verbose) Util.pl("\t\tFastq check failed, archived? leaving as COMPLETE\t"+ oldAADir);
			}
			else {

				//Is it all complete? e.g. MultiQC has run and the symlinked dirs are removed
				File complete = new File (gr.getAutoAnalysisMainDirectory(), "COMPLETE");
				File mqc = new File (gr.getAutoAnalysisMainDirectory(), "MultiQC");
				if (complete.exists() || mqc.exists()) {

					//check number of jobs and number of fastq samples, HTG sometimes adds new Fastq to the old analysis folder, ugg!
					//people also delete the Jobs dir
					if (fastqOK && gr.getJobs()!=null) {
						
						int numJobs = gr.getJobs().length;
						int numFastqSamples = gr.getNumberFastqSampleNames();

						//check that the COMPLETE or MultiQC file is younger than any of the fastq or ora
						File toCheck = null;
						if (complete.exists()) toCheck = complete;
						else toCheck = mqc;
						boolean dateCheck = checkFastqDates( toCheck, gr.getFastqFiles());
						
						if (numFastqSamples > 0 && (numJobs != numFastqSamples || dateCheck == false)) {

							if (verbose) Util.pl("\tNew Fastq found, depreciating\t"+ oldAADir);
							//rename the AutoAnalysis folder so this gets run again on the NEXT check
							File dep = new File (oldAADir.getParentFile(), "Depreciated_"+oldAADir.getName());
							boolean renamed = oldAADir.renameTo(dep);
							if (renamed == false) throw new IOException("FAILED to rename "+oldAADir.getName()+" to "+dep.getName());
						}
						
						else if (verbose) Util.pl("\tCOMPLETE\t"+ oldAADir);
					}
					else if (verbose) Util.pl("\tFastq or Jobs check failed so leave COMPLETE\t"+ oldAADir);

					continue;
				}
				

				//OK, check Jobs directories, might be user deleted, if a run is in progress there will the a Jobs/ dir and one or more subdirs
				if (verbose) Util.pl("\t"+ gr.getOriginalRequestId()+ "\t"+ gr.getAutoAnalysisJobsDirectory());
				File[] jobs = gr.getJobs();
				if (gr.getAutoAnalysisJobsDirectory() == null || jobs == null || jobs.length < 1) {
					if (verbose) Util.pl("\tJobs dir check failed so leave COMPLETE\t"+ oldAADir);
					continue;
				}

				boolean allComplete = true;
				for (File jobDir: jobs) {
					File comp = new File(jobDir, "COMPLETE");
					//the only jobs copied back will have a COMPLETE, otherwise they are waiting on CHPC
					if (comp.exists() == false) {
						allComplete = false; 
						if (verbose) Util.pl("\t\tWAITING ON\t"+jobDir.getName());
					}
					else if (verbose) Util.pl("\t\tCOMPLETE\t"+jobDir.getName());
				}

				//setup for multi qc?
				if (allComplete) {
					grsToMultiQC.add(gr);
					for (File jobDir: jobs) {
						String symLinkName = hciLinkDirectoryString+jobDir.getName();
						Path sl = Paths.get(symLinkName);
						if(Files.exists(sl)) {
							//Delete symlinked job dirs
							if (verbose) Util.pl("\t\tDeleting symlinked job dir\t"+symLinkName);
							Files.delete(sl);
						}
						jobsProcessed.add(jobDir.getName());
					}
				}
			}
		}
	}

	/**Checks if any of the files in the array are newer than the complete file.*/
	private boolean checkFastqDates(File complete, File[] fastqFiles) {
		long ctime = complete.lastModified();
		for (File f: fastqFiles) {		
			if (f.lastModified()> ctime) return false;
		}
		return true;
	}

	private void buildAutoAnalysisJobs() throws IOException {
		// Any jobs?
		if (grsToBuildAutoAnalysis.size() ==0) return;
		Util.pl("\nBuilding new AutoAnalysis jobs...");
		
		for (GNomExRequest r: grsToBuildAutoAnalysis) {
			boolean created = r.createAutoAnalysisJobs(hciLinkDirectory);
			if (created == false) throw new IOException("Failed to create a AutoAnalysis job for "+r.getRequestIdCleaned());
			//some samples may get skipped for lack of sufficient read depth
			if (r.getErrorMessages()!= null && verbose) Util.pl("\tNon- fatal errors observed:\t"+r.getErrorMessages());
		}
	}

	private void parseRequests(GNomExRequest[] requests) throws Exception {
		Util.pl("\nParsing GNomExRequests...");

		boolean test = experimentRequestsToProc.toLowerCase().equals("all") == false;
		TreeMap<String, Integer> molp = new TreeMap<String, Integer>();
		
		for (GNomExRequest r: requests) {
			
			if (test) {
				if (r.getRequestIdCleaned().equals(experimentRequestsToProc) == false) continue;
				else Util.pl("\nTest ExperimentRequest:\n"+r+"\n");
			}
			else if (verbose) Util.pl("\tSummary:\t"+r.simpleToString());

			//do they want any help? the DB query should prevent any of this type of request, so this should be redundant
			if (r.isAutoAnalyze() == false && r.isRequestBioinfoAssistance() == false) {
				if (verbose) Util.pl("\tNo help or analysis requested");
				grsSkipped.add(r);
				continue;
			}
			
			//find the appropriate year for the request
			calendar.setTime(dateFormat.parse(r.getCreationDate()));
			String year = Integer.toString(calendar.get(Calendar.YEAR));
			File repoYearSubDir = experimentalSubDirs.get(year);
			
			//look for the actual request directory
			if (repoYearSubDir == null) throw new IOException ("Failed to find the year "+year+" sub directory in "+experimentalSubDirs +" for "+r.getRequestIdCleaned());
			File requestDirOnRepo = new File (repoYearSubDir, r.getRequestIdCleaned());
			if (requestDirOnRepo.exists() == false) {
				Util.pl("\tERROR: failed to find the Request directory "+requestDirOnRepo+" skipping!");
				r.setErrorMessages("Failed to find the request directory in the repo : "+ requestDirOnRepo);
				grsSkipped.add(r);
				continue;
			}
			r.setRequestDirectory(requestDirOnRepo);

			//is fastq or ora ready, otherwise skip
			if (r.checkFastq() == false) {
				if (verbose) Util.pl("\tFailed to find Fastq ready skipping! No md5? Too new?");
				r.setErrorMessages("Failed to find Fastq ready.");
				grsSkipped.add(r);				
				continue;
			}

			//do they want analysis assistance and provided some notes?
			if (r.isRequestBioinfoAssistance() == true && r.getAnalysisNotes().equals("NA")==false) {
				grsRequestingAnalysisAssistance.add(r);
				if (verbose) Util.pl("\tAdding to Requesting Analysis Assistance.");
			}

			//do they want Auto Analysis?
			if (r.isAutoAnalyze() == true) {
					//check for an existing AutoAnalysis
					if (r.checkForAutoAnalysis()) {
						if (verbose) Util.pl("\tFound exiting AutoAnalysis dir");
						grsWithAutoAnalysis.add(r);
					}

					//run a bunch of other checks to see if an AutoAnalysis could be assembled
					else {
						//is this a supported organism_libraryPrep?
						String orgLib = r.getOrganism()+"_"+Util.WHITE_SPACE.matcher(r.getLibraryPreparation()).replaceAll("");
						if (orgLibWorkflowDocs.containsKey(orgLib)) {
							String[] pathsMultiQCOptions = orgLibWorkflowDocs.get(orgLib);
							
							//check fastq/ ora for dnaAlignQC?
							if (pathsMultiQCOptions[0].contains(dnaAlignQCWFName)) {
								if (r.checkR1R2FastqsPerSample()==false) {
									r.setErrorMessages("For dnaAlignQC workflows, only fastq datasets with _R1_s and _R2_s are supported, UMIs need custom analysis. Check all samples for just _R1_s and _R2_s.");
									grsSkipped.add(r);
									//also add to the assistance in case they would like a custom manual analysis
									grsRequestingAnalysisAssistance.add(r);
									if (verbose) Util.pl("\t"+ r.getErrorMessages());
									continue;
								}
							}
							if (verbose) Util.pl("\tReady for AutoAnalysis");
							r.setWorkflowPaths(pathsMultiQCOptions[0]);
							grsToBuildAutoAnalysis.add(r);
						}
						else {
							//add to missing org lib prep tracker.
							Integer count = molp.get(orgLib);
							if (count == null) molp.put(orgLib, 1);
							else molp.put(orgLib, count+1);
							r.setErrorMessages("Library Protocol not supported at this time, skipping AutoAnalysis ");
							grsSkipped.add(r);
							//also add to the AA in case they would like a custom manual analysis, fastq is ready at this point
							grsRequestingAnalysisAssistance.add(r);
							if (verbose) Util.pl("\t"+ r.getErrorMessages());
						}
					}
				}
		}
		//check if new missingOrgLibPreps
		checkEmailMissingOrgLibPreps(molp);

		//stats
		Util.pl("\t"+grsToBuildAutoAnalysis.size()+ "\tAutoAnalysis to run");
		for (GNomExRequest gr: grsToBuildAutoAnalysis)Util.pl("\t\t"+gr.simpleToString());
		Util.pl("\t"+grsRequestingAnalysisAssistance.size()+ "\tWith custom help requests");
	}


	private void checkEmailMissingOrgLibPreps(TreeMap<String, Integer> molp) {
		//check if different
		boolean different = false;
		for (String key: molp.keySet()) {
			if (missingOrgLibPrep.containsKey(key) == false) {
				different = true;
				break;
			}
			else {
				int newCount = molp.get(key);
				int oldCount = missingOrgLibPrep.get(key);
				if (newCount != oldCount) {
					different = true;
					break;
				}
			}
		}
		
		if (different) {
			missingOrgLibPrep = molp;
			String subject = "GNomEx AutoAnalysis - new unsupported organism : library prep kits found";
			StringBuilder sb = new StringBuilder("The following are all of the requested AlignQCs without a supported workflow :\n\n");
			for (String olp: missingOrgLibPrep.keySet()) {
				sb.append(missingOrgLibPrep.get(olp)); sb.append("\t"); sb.append(olp); sb.append("\n");
			}
			Util.sendMuttEmail(subject, adminEmail, sb.toString());
			Util.pl("\nNew unsupported organism : library prep kits found...");
			Util.pl(sb);
		}
	}

	public static void main(String[] args) {
		if (args.length <= 2){
			printDocs();
			System.exit(0);
		}
		new GNomExAutoAnalysis(args);
	}		


	/**This method will process each argument and assign new variables
	 * @throws Exception */
	public void processArgs(String[] args) throws Exception{
		Util.pl("Arguments: "+ Util.stringArrayToString(args, " ") +"\n");
		Pattern pat = Pattern.compile("-[a-z]");
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'c': configFile = new File(args[++i]); break;
					case 'p': credentialFile = new File(args[++i]); break;
					case 'v': verbose = true; break;
					case 'd': ; break;
					case 'l': ; break;
					default: Util.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Util.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}

		//pull config file, a simple key value file that skips anything with #, splits on tab
		if (configFile == null || configFile.canRead() == false) Util.printErrAndExit("Error: please provide a path to the AutoAnalysis configuration file.\n");

		//check for the credential file for GNomEx db and the Jira API
		if (credentialFile == null || credentialFile.canRead() == false) Util.printErrAndExit("Error: please provide a path to your GNomEx db and Jira API credential file.\n");

		//load and check the configSettings
		loadConfiguration();
		
		loadSupportedWorkflows();
		
		parseProcessCredentialsFile();
		
		Util.pl("\nLoading pattern species matcher for ORA compression...");
		oraSpeciesMatcher = new OraSpeciesMatcher();


	}	

	private void parseProcessCredentialsFile() throws IOException {
		String[] pwLines = Util.loadFile(credentialFile);
		if (pwLines == null || pwLines.length!=2) throw new IOException ("Failed to find two lines in "+ credentialFile);
		// for GNomEx db
		connectionUrl = connectionUrl.replace("XXXXXXXXXX", pwLines[0]);
		if (connectionUrl.contains(pwLines[0])==false) throw new IOException ("Failed to find and or replace XXXXXXXXXX in "+ connectionUrl);
		// for the Jira API,   userName:pw
		jiraApiCredentials = pwLines[1];
		if (jiraApiCredentials.contains(":")==false) throw new IOException ("Failed to find a ':' in the Jira API credentials line 2 in "+ credentialFile);
	}

	private void loadSupportedWorkflows() throws Exception {
		Util.pl("\nParsing Supported Organisms and Library Preps...");
		
		BufferedReader in = Util.fetchBufferedReader(supportedOrgLibWfConfigFile);
		String[] fields;
		String line;
		
		orgLibWorkflowDocs = new HashMap<String, String[]>();
		boolean errors = false;
		while ((line = in.readLine())!=null) {
			line = line.trim();
			if (line.startsWith("#") || line.length()==0) continue;
			//Organism   LibraryKit(s)   WFDirFile(s)   MultiQCOptions
			//    0          1                2              3
			fields = Util.TAB.split(line);
			if (fields.length != 4) {
				Util.pl("\tERROR: missing fields in workflow config line -> "+line);
				errors = true;
			}
			else {
				if (verbose) Util.pl("\t"+line);
				String[] libPreps = Util.SEMI_COLON_SPACE.split(fields[1]);
				for (String lp: libPreps) {
					String key = fields[0].trim()+"_"+Util.WHITE_SPACE.matcher(lp).replaceAll("");
					orgLibWorkflowDocs.put(key, new String[] {fields[2], fields[3]});
					if (verbose) Util.pl("\t\t'"+ key +"' -> "+fields[2]+" and "+fields[3]);
				}
			}
		}
		in.close();
		if (errors) throw new IOException();
	}

	private void loadConfiguration() {
		Util.pl("\nParsing Configuration File...");
		HashMap<String,String> configSettings = Util.loadFileIntoHash(configFile, 0, 1);

		//connectionUrl
		connectionUrl = configSettings.get("connectionUrl");
		if (connectionUrl == null) Util.printErrAndExit("\nError: failed to find the 'connectionUrl' key in "+ configFile);
		
		// experimentLinkUrl
		experimentLinkUrl = configSettings.get("experimentLinkUrl");
		if (experimentLinkUrl == null) Util.printErrAndExit("\nError: failed to find the 'experimentLinkUrl' key in "+ configFile);
		
		// jiraUrl for submitting help requests
		jiraUrl = configSettings.get("jiraUrl");
		if (jiraUrl == null) Util.printErrAndExit("\nError: failed to find the 'jiraUrl' help request link in "+ configFile);
		
		// jiraApiUrl for actually creating tickets via the api
		jiraApiUrl = configSettings.get("jiraApiUrl");
		if (jiraApiUrl == null) Util.printErrAndExit("\nError: failed to find the 'jiraApiUrl' key in "+ configFile);
		
		// dataPolicyUrl
		dataPolicyUrl = configSettings.get("dataPolicyUrl");
		if (dataPolicyUrl == null) Util.printErrAndExit("\nError: failed to find the 'dataPolicyUrl' key in "+ configFile);
		
		//adminEmail
		adminEmail = configSettings.get("adminEmail");
		if (adminEmail == null) Util.printErrAndExit("\nError: failed to find the 'adminEmail' key in "+ configFile);
		
		//analysisReadyEmail
		analysisReadyEmail = configSettings.get("analysisReadyEmail");
		if (analysisReadyEmail == null) Util.printErrAndExit("\nError: failed to find the 'analysisReadyEmail' key in "+ configFile);
		
		//testRequest
		experimentRequestsToProc = configSettings.get("testRequest");
		if (experimentRequestsToProc == null) Util.printErrAndExit("\nError: failed to find the 'testRequest' key in "+ configFile);
		
		//HoursToWait
		if (configSettings.containsKey("hoursToWait") == false) Util.printErrAndExit("\nError: failed to find the 'hoursToWait' key in "+ configFile);
		hoursToWait = Double.parseDouble(configSettings.get("hoursToWait"));
		waitTime = (long)Math.round(hoursToWait * 60.0 * 60.0 * 1000.0);

		//experimental directories
		String experimentDirString = configSettings.get("experimentDir");
		if (experimentDirString == null) Util.printErrAndExit("\nError: failed to find the 'experimentDir' key in "+ configFile);
		File ed = new File(experimentDirString);
		if (ed.exists() == false) Util.printErrAndExit("\nError: failed to find the 'experimentDir' directory in "+ configFile);
		experimentalSubDirs = Util.extractDirectories(ed);
		if (experimentalSubDirs.containsKey("2023") == false) Util.printErrAndExit("\nError: failed to find the '2023' directory in "+ ed);

		if (configSettings.containsKey("hciLinkDirectory") == false) Util.printErrAndExit("\nError: failed to find the 'hciLinkDirectory' key in "+ configFile);
		hciLinkDirectoryString = configSettings.get("hciLinkDirectory");
		if (hciLinkDirectoryString.endsWith("/")==false) hciLinkDirectoryString = hciLinkDirectoryString+"/";
		hciLinkDirectory = new File (hciLinkDirectoryString);	
		if (hciLinkDirectory.canWrite() == false) Util.printErrAndExit("\nError: cannot write to the 'hciLinkDirectory'"+ hciLinkDirectory);
		
		//temp dir for jira ticket creation, hciTempDirectory
		if (configSettings.containsKey("hciTempDirectory") == false) Util.printErrAndExit("\nError: failed to find the 'hciTempDirectory' key in "+ configFile);
		hciTempDirectory = new File (configSettings.get("hciTempDirectory"));
		if (hciTempDirectory.exists() == false && hciTempDirectory.mkdirs() == false) Util.printErrAndExit("\nError: failed to find the 'hciTempDirectory' file in "+ configFile);

		
		//JobCleaner path command
		if (configSettings.containsKey("useqJobCleaner") == false) Util.printErrAndExit("\nError: failed to find the 'useqJobCleaner' key in "+ configFile);
		useqJobCleaner = new File (configSettings.get("useqJobCleaner"));
		if (useqJobCleaner.exists() == false) Util.printErrAndExit("\nError: failed to find the 'useqJobCleaner' app in "+ configFile);
		
		//AggregateQCStats2
		if (configSettings.containsKey("useqAggregateQCStats2") == false) Util.printErrAndExit("\nError: failed to find the 'useqAggregateQCStats2' key in "+ configFile);
		useqAggregateQCStats2 = new File (configSettings.get("useqAggregateQCStats2"));
		if (useqAggregateQCStats2.exists() == false) Util.printErrAndExit("\nError: failed to find the 'useqAggregateQCStats2' app in "+ configFile);

		// supportedOrgLibWfConfigFile
		if (configSettings.containsKey("supportedOrgLibWfConfigFile") == false) Util.printErrAndExit("\nError: failed to find the 'supportedOrgLibWfConfigFile' key in "+ configFile);
		supportedOrgLibWfConfigFile = new File (configSettings.get("supportedOrgLibWfConfigFile"));
		if (supportedOrgLibWfConfigFile.exists() == false) Util.printErrAndExit("\nError: failed to find the 'supportedOrgLibWfConfigFile' app in "+ configFile);
		
		//builtJiraTickets to avoid creating duplicate jira help tickets
		if (configSettings.containsKey("builtJiraTickets") == false) Util.printErrAndExit("\nError: failed to find the 'builtJiraTickets' key in "+ configFile);
		builtJiraTickets = new File (configSettings.get("builtJiraTickets"));
		if (builtJiraTickets.exists() == false) Util.printErrAndExit("\nError: failed to find the 'builtJiraTickets' file specified in "+ configFile);
		
		// sampleSpeciesFile for demux ORA
		if (configSettings.containsKey("sampleSpeciesFile") == false) Util.printErrAndExit("\nError: failed to find the 'sampleSpeciesFile' key in "+ configFile);
		sampleSpeciesFile = new File (configSettings.get("sampleSpeciesFile"));
		try {
			if (sampleSpeciesFile.exists() == false) sampleSpeciesFile.createNewFile();
			if (sampleSpeciesFile.getParentFile().canWrite() == false) throw new IOException();
		} catch (IOException e) {
			Util.printErrAndExit("\nError: cannot modify the 'sampleSpeciesFile' or parent directory "+sampleSpeciesFile);
		}
		
		
		//print out settings
		Util.pl("Config Settings..."+
				"\n  adminEmail\t"+ adminEmail+
				"\n  analysisReadyEmail\t"+analysisReadyEmail+
				"\n  hoursToWait\t"+ hoursToWait+
				"\n  verbose\t"+verbose+
				"\n  connectionUrl\t"+ connectionUrl+
				"\n  experimentDirString\t"+ experimentDirString+
				"\n  experimentLinkUrl\t"+ experimentLinkUrl+
				"\n  experimentRequestsToProc\t"+ experimentRequestsToProc+
				"\n  hciLinkDirectory\t"+ hciLinkDirectory+
				"\n  hciTempDirectory\t"+ hciTempDirectory+
				"\n  jiraUrl\t"+ jiraUrl+
				"\n  jiraApiUrl\t"+ jiraApiUrl+
				"\n  dataPolicyUrl\t"+ dataPolicyUrl+
				"\n  supportedOrgLibWfConfigFile\t"+ supportedOrgLibWfConfigFile+
				"\n  sampleSpeciesFile\t"+sampleSpeciesFile+
				"\n  useqJobCleaner\t"+ useqJobCleaner+
				"\n  useqAggregateQCStats2\t"+ useqAggregateQCStats2
				);
		
	}


	public static void printDocs(){
		Util.pl("\n" +
				"**************************************************************************************\n" +
				"**                           GNomEx Auto Analysis: Nov 2025                         **\n" +
				"**************************************************************************************\n" +
				"GAA orchestrates setting up auto analysis jobs from GNomEx Experiment Fastq. It looks\n"+
				"for ERs in the GNomEx db where the user has selected a genome build to align to,\n"+
				"finds those with a match to the supported organisms and library types, assembles the\n"+
				"directories, links in the fastq, and adds a RUNME file containing info for the\n "+
				"ChpcAutoAnalysis daemon. Uses mutt for mail so be sure to install it.\n"+

				"\nOptions:\n"+
				"   -c Path to the AutoAnalysis configuration file.\n"+
				"   -v Verbose debugging output.\n"+
				"   -p Path to a file containing the GNomEx db user pw on line one and Jira API\n"+
				"        userName:pw on line two.\n"+

				"\nExample: java -jar -Xmx2G GNomExAutoAnalysis.jar -c autoAnalysis.config.txt -v -p \n"+
				"               gnomExDbJiraApi.cred.txt.\n\n"+


				"**************************************************************************************\n");

	}


}



