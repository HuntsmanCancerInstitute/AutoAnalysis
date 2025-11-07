package edu.utah.hci.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class GNomExRequest {
	
	private String[] dbResults;
	private String originalRequestId;
	private String requestIdCleaned;
	private String creationDate;
	private String requestorEmail;
	private String labGroupLastName;
	private String labGroupFirstName;
	private String organism;
	private String libraryPreparation;
	private String analysisNotes;
	private boolean autoAnalyze;
	private boolean requestBioinfoAssistance;
	
	private File requestDirectory = null;
	private File[] fastqFiles = null;
	private int numberFastqSampleNames = -1;
	private File autoAnalysisMainDirectory = null;
	private File autoAnalysisJobsDirectory = null;
	private File[] jobs = null;
	
	/*Semi-colon space delimited, full path, on Redwood, if a dir the contents will be copied into each job.*/
	private String workflowPaths = null;
	
	private String errorMessages = null;

	
	/*
	 request.number	//0
	request.createDate	//1
	appuser.email	//2
	lab.lastname	//3
	lab.firstname	//4
	organism.organism	//5
	application.application	//6
	request.analysisInstructions	//7   NA or freeform txt
	request.alignToGenomeBuild	//8  NA, N, or Y
	request.bioInformaticsAssist	//9  N or Y
	request.codeRequestStatus	//10
	request.lastModifyDate	//11
	 * */
	public GNomExRequest (String[] fields) {
		dbResults = fields;
		//watch out for requests with numbers trailing the R, e.g. 22564R1 -> converted to just 22564R which is the dir name in the repo
		int index = fields[0].lastIndexOf("R")+1;
		requestIdCleaned = fields[0].substring(0, index);
		originalRequestId = fields[0];
		
		creationDate = fields[1];
		requestorEmail = fields[2].trim();
		labGroupLastName = fields[3];
		labGroupFirstName = fields[4];
		organism = fields[5].trim();
		libraryPreparation = fields[6].trim();
		analysisNotes = fields[7].trim();
		autoAnalyze = fields[8].contains("Y");
		requestBioinfoAssistance = fields[9].contains("Y");
	}
	
	public String toString() {
		StringBuilder sb = new StringBuilder(dbResults[0]);
		for (int i=1; i< dbResults.length; i++) {
			sb.append("\n");
			sb.append(dbResults[i]);
		}
		return sb.toString();
	}
	
	public String simpleToString() {
		StringBuilder sb = new StringBuilder();
		sb.append(originalRequestId); sb.append("\t");
		sb.append(creationDate); sb.append("\t");
		sb.append(organism); sb.append("\t");
		sb.append(libraryPreparation); sb.append("\t");
		sb.append(autoAnalyze);
		return sb.toString();
	}
	
	public boolean createAutoAnalysisJobs(File chpcLinkDirectory) {
		try {
			//create the dir AutoAnalysis_22Dec2023
			autoAnalysisMainDirectory = new File (requestDirectory, "AutoAnalysis_"+Util.getDateNoSpaces());
			autoAnalysisMainDirectory.mkdirs();
			autoAnalysisJobsDirectory = new File(autoAnalysisMainDirectory, "Jobs");
			autoAnalysisJobsDirectory.mkdir();
			if (autoAnalysisJobsDirectory.exists()==false) throw new IOException("ERROR: failed to create a job directory -> "+autoAnalysisJobsDirectory);

			//pull sample names
			HashSet<String> sampleIds = new HashSet<String>();
			for (File f: fastqFiles) {
				String[] split = Util.UNDERSCORE.split(f.getName());
				sampleIds.add(split[0]);
			}

			//create sub directories, copy in the workflow docs, link in the fastq lines
			ArrayList<File> toLink = new ArrayList<File>();
			HashSet<String> sampleRepeats = new HashSet<String>();
			for (String sampleId: sampleIds) {

				// make the sub dir
				File subDir = new File (autoAnalysisJobsDirectory, sampleId);
				subDir.mkdir();

				//link in the fastqs and parse the unique sample names
				toLink.clear();
				sampleRepeats.clear();
				for (File f: fastqFiles) {
					if (f.getName().startsWith(sampleId+ "_")) {
						toLink.add(f);
						//pull sample names, needed for CellRanger, HTG often adds new sequencing
						//20758X2_230503_A00421_0548_AH7M32DRX3_S2_L002_I1_001.fastq.gz
						//20758X2_230731_A00421_0576_BHGMFWDRX3_S2_L002_I1_001.fastq.gz
						//   0       1      2     3      4       5
						String[] s = Util.UNDERSCORE.split(f.getName());
						if (s.length>=5) sampleRepeats.add(s[0]+"_"+s[1]+"_"+s[2]+"_"+s[3]+"_"+s[4]);
						else throw new IOException ("FAILED to find at least 5 _ separated name elements in "+f.getName());
					}
				}
				//check they have sufficient number of reads, just skip the particular job since the others are probably OK
				boolean fileCountOK = checkFastqsHaveSufficientReads(toLink);
				if (fileCountOK == false) {
					String message = "Skipping "+sampleId+", one or more of the fastq files did not have sufficient reads.";
					if (errorMessages == null) errorMessages = message;
					else errorMessages = errorMessages+"; "+message;
					continue;
				}
				
				Util.createSymbolicLinks(toLink, subDir);
				String sampleNames= Util.stringHashToString(sampleRepeats, ",");
				
				//link the sub dir to the chpcLinkDirectory
				toLink.clear();
				toLink.add(subDir);
				Util.createSymbolicLinks(toLink, chpcLinkDirectory);
				
				//add a RUNME.txt file
				String runMe = 
						"sampleNames\t"+sampleNames+
						"\nworkflowPaths\t"+workflowPaths+
						"\norganism\t"+ organism+
						"\nlibraryPrep\t"+libraryPreparation+"\n";
				Util.writeString(runMe, new File(subDir, "RUNME"));
			} 
			return true;
		} catch (Exception e) {
			Util.el("ERROR: making AutoAnalysis job for "+requestIdCleaned);
			e.printStackTrace();
			Util.deleteDirectory(autoAnalysisMainDirectory);
		}
		return false;
	}
	
	/**Returns false if all samples don't have just  _R1_ and _R2_ s named fastq or single ora, otherwise probably a UMI is present.*/
	public boolean checkR1R2FastqsPerSample() {

			//21369X1_20231010_LH00227_0016_B227FWCLT3_S15_L001_R1_001.fastq.gz
			//21369X1_20231010_LH00227_0016_B227FWCLT3_S15_L001_R2_001.fastq.gz
			//or
			//21369X1_20231010_LH00227_0016_B227FWCLT3_S15_L002_R-interleaved_001.fastq.ora
			HashMap<String, ArrayList<String>> sampleFastqs = new HashMap<String, ArrayList<String>>();
			String fileName = null;
			for (File f: fastqFiles) {
				fileName = f.getName();
				String[] split = Util.UNDERSCORE.split(fileName);
				ArrayList<String> fileNames = sampleFastqs.get(split[0]);
				if (fileNames == null) {
					fileNames = new ArrayList<String>();
					sampleFastqs.put(split[0], fileNames);
				}
				fileNames.add(fileName);
			}
			//check that each sample has the same number of _R1_ and _R2_, ora, and nothing else
			for (ArrayList<String> al: sampleFastqs.values()) {
				int r1 = 0;
				int r2 = 0;
				int ora = 0;
				int rOther = 0;
				//if (al.size()!=2) return false;
				for (String s: al) {
					if (s.contains("_R1_")) r1++;
					else if (s.contains("_R2_")) r2++;
					else if (s.contains("_R-interleaved_")) ora++;
					else rOther++;
				}
				//mixed num fastq or non fastq
				if (r1!=r2 || rOther!=0) return false;
				//mixed fastq and ora, OK for 10x but not standard bulk RnaSeq or DnaSeq
				if (r1>0 && ora>1) return false;
				//no fastq or ora
				if (r1==0 && ora ==0) return false;
			}
			return true;
	}
	
	public int countNumberFastqSamples() {
		//21369X1_20231010_LH00227_0016_B227FWCLT3_S15_L001_R1_001.fastq.gz
		//26155X1_20250425_LH00227_0152_A22Y7LTLT3_S17_L002_R-interleaved_001.fastq.ora
		HashSet<String> sampleFastqs = new HashSet<String>();
		String fileName = null;
		for (File f: fastqFiles) {
			fileName = f.getName();
			String[] split = Util.UNDERSCORE.split(fileName);
			sampleFastqs.add(split[0]);
		}
		return sampleFastqs.size();
	}
	
	/**Returns false if all of the fastqs don't have at least the minimumFastqCount. Skip ora
	 * @throws IOException */
	public boolean checkFastqsHaveSufficientReads(ArrayList<File> fastqFiles) throws IOException {
		for (File f: fastqFiles) {
			//skip ora
			if (f.getName().endsWith(".ora")) continue;
			int lineCount = 0;
			String line = null;
			BufferedReader in = Util.fetchBufferedReader(f);
			while ((line = in.readLine()) !=null) {
				lineCount++;
				if (lineCount >= GNomExAutoAnalysis.minimumFastqFileLineCount) break;
			}
			in.close();
			if (lineCount < GNomExAutoAnalysis.minimumFastqFileLineCount) return false;	
		}
		return true;
	}

	
	public boolean checkForAutoAnalysis() {
		File[] dirs = Util.extractFilesPrefix(requestDirectory, "AutoAnalysis_");
		if (dirs.length == 0) return false;
		else if (dirs.length == 1) autoAnalysisMainDirectory = dirs[0];
		else {
			//find most recent
			File mostRecent = dirs[0];
			long mostRecentTime = mostRecent.lastModified();
			for (int i=1; i< dirs.length; i++) {
				if (dirs[i].lastModified() > mostRecentTime) {
					mostRecent = dirs[i];
					mostRecentTime = mostRecent.lastModified();
				}
			}
			autoAnalysisMainDirectory = mostRecent;
		}
		//any Jobs directory?
		File jd = new File(autoAnalysisMainDirectory, "Jobs");
		if (jd.exists()) {
			autoAnalysisJobsDirectory = jd;
			jobs = Util.extractOnlyDirectories(jd);
		}
		
		return true;
	}
	
	public boolean checkFastq() {

		File fastqDirectory = new File(requestDirectory, "Fastq");	

		if (fastqDirectory.exists() == false) return false;

		//contains a file with md5 in the name
		File[] allFiles = Util.extractFiles(fastqDirectory);
		boolean foundMd5 = false;
		for (File f: allFiles) {		
			if (f.getName().contains("md5")) {
				foundMd5 = true;
				break;
			}
		}	
		if (foundMd5 == false) return false;

		//find the fastq files (gz or ora) 
		File[][] seqFiles = new File[2][];
		seqFiles[0] = Util.extractFiles(fastqDirectory, "q.gz");
		seqFiles[1] = Util.extractFiles(fastqDirectory,".ora");
		fastqFiles = Util.collapseFileArray(seqFiles);
		
		//any fastqs? might be deleted
		if (fastqFiles.length == 0) return false;
		
		//check age, don't want to work on very recent fastqs since these might be in process of being copied over from demux
		long currentTime = System.currentTimeMillis() - 3600000;  //1hr
		for (File f: fastqFiles) if ((currentTime - f.lastModified())<0) return false;
		
		//count fastq sample names
		numberFastqSampleNames = countNumberFastqSamples();
		return true;
	}
	
	public String getJiraTicketData() {
		/*
		{"fields": {"project": {"id": "14900"},
		"summary": "Testing Jira API - IGNORE - 2",
		"description": "Creating of an issue using IDs for projects and issue types using the REST API",
		"customfield_21408": "123456R",
		"customfield_11801": "Eric Jackson Lab",
		"issuetype": {"id": "12700"}}}
		 */
		String labGroup = labGroupLastName+", "+labGroupFirstName;
		
		String sum = requestorEmail+" - "+labGroup+" - "+libraryPreparation+" - "+requestIdCleaned;
		
		StringBuilder desc = new StringBuilder("GNomEx LIMS Analysis Request");
		desc.append("\\nRequestor:\\t"); desc.append(requestorEmail);
		desc.append("\\nOrganism:\\t"); desc.append( organism);
		desc.append("\\nLibraryPrep:\\t"); desc.append( libraryPreparation);
		desc.append("\\nCreationDate:\\t"); desc.append( creationDate);
		desc.append("\\nRequestingAutoAnalysis:\\t"); desc.append(autoAnalyze);
		if (autoAnalysisMainDirectory != null) {
			desc.append("\\nAutoAnalysisDir:\\t"); 
			desc.append(autoAnalysisMainDirectory.getName());
		}
		
		desc.append("\\nRequestingAnalysisAssistance:\\t"); desc.append(requestBioinfoAssistance);
		if (errorMessages!=null) {
			desc.append("\\nIssues/Errors:\\t"); 
			desc.append(errorMessages);
		}
		if (analysisNotes.equals("NA")==false) {
			desc.append("\\nAnalysisNotes:\\n"); 
			desc.append(analysisNotes.replaceAll("\"", "'"));
		}
		StringBuilder sb = new StringBuilder("{\"fields\": {\"project\": {\"id\": \"14900\"},\n");
		sb.append("\"summary\": \""+sum+"\",\n");
		sb.append("\"description\": \""+desc+"\",\n");
		sb.append("\"customfield_21408\": \""+requestIdCleaned +"\",\n");
		sb.append("\"customfield_11801\": \""+labGroup+"\",\n");
		sb.append("\"issuetype\": {\"id\": \"12700\"}}}\n");
		return sb.toString();
	}

	public String getRequestIdCleaned() {
		return requestIdCleaned;
	}

	public String getCreationDate() {
		return creationDate;
	}

	public String getRequestorEmail() {
		return requestorEmail;
	}

	public String getLabGroupLastName() {
		return labGroupLastName;
	}

	public String getLabGroupFirstName() {
		return labGroupFirstName;
	}

	public String getOrganism() {
		return organism;
	}

	public String getLibraryPreparation() {
		return libraryPreparation;
	}

	public String getAnalysisNotes() {
		return analysisNotes;
	}

	public File getRequestDirectory() {
		return requestDirectory;
	}

	public void setRequestDirectory(File requestDirectory) {
		this.requestDirectory = requestDirectory;
	}

	public String getErrorMessages() {
		return errorMessages;
	}

	public void setErrorMessages(String errorMessages) {
		this.errorMessages = errorMessages;
	}

	public String getOriginalRequestId() {
		return originalRequestId;
	}

	public File getAutoAnalysisMainDirectory() {
		return autoAnalysisMainDirectory;
	}

	public void setAutoAnalysisMainDirectory(File autoAnalysisMainDirectory) {
		this.autoAnalysisMainDirectory = autoAnalysisMainDirectory;
	}

	public File getAutoAnalysisJobsDirectory() {
		return autoAnalysisJobsDirectory;
	}

	public void setAutoAnalysisJobsDirectory(File autoAnalysisJobsDirectory) {
		this.autoAnalysisJobsDirectory = autoAnalysisJobsDirectory;
	}

	public String getWorkflowPaths() {
		return workflowPaths;
	}

	public void setWorkflowPaths(String workflowPaths) {
		this.workflowPaths = workflowPaths;
	}

	public boolean isAutoAnalyze() {
		return autoAnalyze;
	}

	public boolean isRequestBioinfoAssistance() {
		return requestBioinfoAssistance;
	}

	public File[] getJobs() {
		return jobs;
	}

	public int getNumberFastqSampleNames() {
		return numberFastqSampleNames;
	}

	public File[] getFastqFiles() {
		return fastqFiles;
	}
}
