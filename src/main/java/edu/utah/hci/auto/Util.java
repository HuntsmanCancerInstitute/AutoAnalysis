package edu.utah.hci.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Util {
	
	public static final Pattern TAB = Pattern.compile("\t");
	public static final Pattern SEMI_COLON_SPACE = Pattern.compile("\\s*;\\s*");
	public static final Pattern COMMA_SPACE = Pattern.compile("\\s*,\\s*");
	public static final Pattern UNDERSCORE = Pattern.compile("_");
	public static final Pattern WHITE_SPACE = Pattern.compile("\\s+");
	public static final Pattern SLURM_JOBID = Pattern.compile("slurm-(\\d+).out");
	public static final Pattern FORWARD_SLASH = Pattern.compile("/");
	public static final Random random = new Random();
	
	/**Executes a String of shell script commands via a temp file.  Only good for Unix. Returns the exit code. Prints errors if encountered.
	 * @throws IOException */
	public static int executeShellScriptReturnExitCode (String shellScript, File tempDirectory) throws Exception{
		//make shell file
		File shellFile = new File (tempDirectory, new Double(Math.random()).toString().substring(2)+"_TempFile.sh");
		shellFile.deleteOnExit();
		//write to shell file
		Util.writeString(shellScript, shellFile);
		shellFile.setExecutable(true);
		//execute
		String[] cmd = new String[]{"bash", shellFile.getCanonicalPath()};
		int res = executeReturnExitCode (cmd);
		shellFile.delete();
		return res; 
	}
	
	/**Uses ProcessBuilder to execute a cmd, combines standard error and standard out into one and printsToLogs if indicated.
	 * Returns exit code, 0=OK, >0 a problem
	 * @throws IOException */
	public static int executeReturnExitCode(String[] command) throws Exception{
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process proc = pb.start();
		BufferedReader data = new BufferedReader(new InputStreamReader(proc.getInputStream()));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = data.readLine()) != null) {
			sb.append(line);
			sb.append("\n");
		}
		int exitCode = proc.waitFor();
		if (exitCode !=0) pl("Non zero exit code: "+sb.toString());
		return exitCode;
	}
	
	public static void sendEmail(String subject, String emailAddress, String body, boolean verbose) {
		try {
			//doesn't work anymore? WTH!! sendmail 'david.austin.nix@gmail.com,david.nix@hci.utah.edu' < sm.txt
			//put email recipients in the txt file then: sendmail -t < sm.txt
			String message = "Subject: " +subject+ 
					"\nTo: "+emailAddress+
					"\nFrom: noreply_auto_analysis@hci.utah.edu\n\n"+body+"\n";
			File tmpDir = new File(System.getProperty("java.io.tmpdir"));
			if (tmpDir.exists()==false) throw new Exception("ERROR: failed to find tmp dir. "+tmpDir);
			File tmpFile = new File(tmpDir,"autoAnalysis."+ random.nextInt(10000)+ ".tmp.txt");
			tmpFile.deleteOnExit();
			Util.writeString(message, tmpFile);

			//execute via a shell script
			String cmd = "sendmail -t < "+tmpFile.getCanonicalPath();			
			int exit = Util.executeShellScriptReturnExitCode(cmd, tmpDir);			
			//this never happens?
			if (exit != 0) throw new IOException ("Non zero exit code from: "+cmd);
			if (verbose) {
				Util.pl("\nEmailing:"+subject+"\nCmd: '"+cmd+"\nTxtFile:\n'"+message+"'\n");
			}
		} catch (Exception e) {
			pl("\nERROR: sending email "+e.getMessage()+"\n"+e.getStackTrace());
		}
	}
	
	public static void sendMuttEmail(String subject, String emailAddresses, String body) {
		String cmd = "";
		try {
			
			/*
			 echo "This is the email body." | \
  				mutt -s "Subject Line Testing" -e "my_hdr Reply-To: noreply_auto_analysis@hci.utah.edu" \
  				david.austin.nix@gmail.com,david.nix@hci.utah.edu

			 */

			//execute via a shell script
			cmd = "echo \""+body+"\" | mutt -s \""+subject+"\" -e 'my_hdr Reply-To: noreply_auto_analysis@hci.utah.edu' "+emailAddresses;
		
			//check for tmp dir
			File tmpDir = new File(System.getProperty("java.io.tmpdir"));
			if (tmpDir.exists()==false) throw new Exception("ERROR: failed to find tmp dir. "+tmpDir);
			
			int exit = Util.executeShellScriptReturnExitCode(cmd, tmpDir);			
			//this never happens?
			if (exit != 0) throw new IOException ("Non zero exit code from: "+cmd);

		} catch (Exception e) {
			pl("\nERROR: sending mutt email "+e.getMessage()+"\n"+e.getStackTrace()+"\n"+cmd);
		}
	}
	
	public static final String[] months = {"Jan","Feb","Mar","Apr","May","June","July", "Aug","Sept","Oct","Nov","Dec"};
	/**Returns a nicely formated time, 15 May 2004 21:53 */
	public static String getDateTime(){
		GregorianCalendar c = new GregorianCalendar();
		int minutes = c.get(Calendar.MINUTE);
		String min;
		if (minutes < 10) min = "0"+minutes;
		else min = ""+minutes;
		return c.get(Calendar.DAY_OF_MONTH)+" "+months[c.get(Calendar.MONTH)]+" "+ c.get(Calendar.YEAR)+" "+c.get(Calendar.HOUR_OF_DAY)+":"+min;
	}
	
	public static File copyInWorkflowDocs(File[] workflowDocs, File jobDir) throws IOException {
		File shellScript = null;
		try {
			for (File f: workflowDocs) {
				if (f.isDirectory()) continue;
				File copy = new File(jobDir, f.getName());
				Util.copyViaFileChannel(f, copy);
				if (copy.getName().endsWith(".sh")) shellScript = copy;
			}
			if (shellScript == null) throw new IOException("Failed to find the workflow xxx.sh file in "+workflowDocs[0].getParent());
		} catch (NullPointerException e) {
			throw new IOException ("Problem copying files into "+jobDir.getCanonicalPath()+" see \n"+e.getMessage());
		}
		return shellScript;
	}
	
	/** Fast & simple file copy. From GForman http://www.experts-exchange.com/M_500026.html
	 * Hit an odd bug with a "Size exceeds Integer.MAX_VALUE" error when copying a vcf file. -Nix.*/
	public static boolean copyViaFileChannel(File source, File dest){
		FileChannel in = null, out = null;
		try {
			in = new FileInputStream(source).getChannel();
			out = new FileOutputStream(dest).getChannel();
			long size = in.size();
			MappedByteBuffer buf = in.map(FileChannel.MapMode.READ_ONLY, 0, size);
			out.write(buf);
			
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} finally {
			if (in != null)
				try {
					in.close();
				} catch (IOException e) {}
			if (out != null)
				try {
					out.close();
				} catch (IOException e) {}
		}
		return true;
	}
	
	/**Loads a file's lines into a String[], skips blank lines, comments, trims lines, gz/zip OK*/
	public static String[] loadFile(File file){
		ArrayList<String> a = new ArrayList<String>();
		try{
			BufferedReader in = fetchBufferedReader(file);
			String line;
			while ((line = in.readLine())!=null){
				line = line.trim();
				if (line.startsWith("#") || line.length() == 0) continue;
				a.add(line);
			}
			in.close();
		}catch(Exception e){
			System.out.println("Prob loadFile into String[]");
			e.printStackTrace();
			return null;
		}
		String[] strings = new String[a.size()];
		a.toArray(strings);
		return strings;
	}
	
	/**Loads a file's lines into a hash set, keys only.*/
	public static HashSet<String> loadFileIntoHashSet(File file){
		HashSet<String> names = new HashSet<String>();
		try{
			BufferedReader in = fetchBufferedReader(file);
			String line;
			while ((line = in.readLine())!=null){
				line = line.trim();
				if (line.length() ==0) continue;
				names.add(line);
			}
			in.close();
		}catch(Exception e){
			System.out.println("Prob loadFileInttoHash()");
			e.printStackTrace();
		}
		return names;
	}
	
	/**Writes each key tab value on a separate line to the file.*/
	public static boolean writeHashSet(HashSet set, File file){
		try{
			PrintWriter out  = new PrintWriter(new FileWriter(file));
			Iterator it = set.iterator();
			while (it.hasNext()){
				out.println(it.next());
			}
			out.close();
		}catch (Exception e){
			e.printStackTrace();
			return false;
		}
		return true;	
	}
	
	/**Returns a String separated by the separator given an ArrayList of objects.*/
	public static String arrayListToString(ArrayList al, String separator){
		int len = al.size();
		if (len==0) return "";
		if (len==1) return al.get(0).toString();
		StringBuffer sb = new StringBuffer(al.get(0).toString());
		for (int i=1; i<len; i++){
			sb.append(separator);
			sb.append(al.get(i).toString());
		}
		return sb.toString();
	}
	
	/**Executes tokenized params on command line, use full paths.
	 * Put each param in its own String.  
	 * Returns null if a problem is encountered.
	 */
	public static String[] executeCommandLine(String[] command){
		ArrayList<String> al = new ArrayList<String>();
		try {
			Runtime rt = Runtime.getRuntime();
			//rt.traceInstructions(true); //for debugging
			//rt.traceMethodCalls(true); //for debugging
			Process p = rt.exec(command);
			BufferedReader data = new BufferedReader(new InputStreamReader(p.getInputStream()));
			//BufferedReader error = new BufferedReader(new InputStreamReader(p.getErrorStream())); //for debugging
			String line;
			while ((line = data.readLine()) != null){
				al.add(line);
			}
			data.close();

		} catch (Exception e) {
			System.out.println("Problem executingCommandLine(), command -> "+stringArrayToString(command," "));
			e.printStackTrace();
			return null;
		}
		String[] res = new String[al.size()];
		al.toArray(res);
		return res;
	}
	
	/**Attempts to delete a directory and it's contents.
	 * Returns false if all the file cannot be deleted or the directory is null.
	 * Files contained within scheduled for deletion upon close will cause the return to be false.*/
	public static void deleteDirectory(File dir){
		if (dir == null || dir.exists() == false) return;
		if (dir.isDirectory()) {
			File[] children = dir.listFiles();
			for (int i=0; i<children.length; i++) {
				deleteDirectory(children[i]);
			}
			dir.delete();
		}
		dir.delete();
		
		if (dir.exists()) deleteDirectoryViaCmdLine(dir);
	}

	/**Fetches all files with a given extension in a directory recursing through sub directories.
	 * Will return a file if a file is given with the appropriate extension, or null.*/
	public static File[] fetchFilesRecursively (File directory, String extension){
		if (directory.isDirectory() == false){
			return extractFiles(directory, extension);
		}
		ArrayList<File> al = fetchAllFilesRecursively (directory, extension);
		File[] files = new File[al.size()];
		al.toArray(files);
		return files;
	}
	
	/**Fetches all files with a given extension in a directory recursing through sub directories.*/
	public static ArrayList<File> fetchAllFilesRecursively (File directory, String extension){
		ArrayList<File> files = new ArrayList<File>(); 
		File[] list = directory.listFiles();
		for (int i=0; i< list.length; i++){
			if (list[i].isDirectory()) {
				ArrayList<File> al = fetchAllFilesRecursively (list[i], extension);
				files.addAll(al);
			}
			else{
				if (list[i].getName().endsWith(extension)) files.add(list[i]);
			}
		}
		return files;
	}
	
	/**Extracts the full path file names of all the files in a given directory with a given extension (ie txt or .txt).
	 * If the dirFile is a file and ends with the extension then it returns a File[] with File[0] the
	 * given directory. Returns null if nothing found. Case insensitive.*/
	public static File[] extractFiles(File dirOrFile, String extension){
		if (dirOrFile == null || dirOrFile.exists() == false) return null;
		File[] files = null;
		Pattern p = Pattern.compile(".*"+extension+"$", Pattern.CASE_INSENSITIVE);
		Matcher m;
		if (dirOrFile.isDirectory()){
			files = dirOrFile.listFiles();
			int num = files.length;
			ArrayList chromFiles = new ArrayList();
			for (int i=0; i< num; i++)  {
				m= p.matcher(files[i].getName());
				if (m.matches()) chromFiles.add(files[i]);
			}
			files = new File[chromFiles.size()];
			chromFiles.toArray(files);
		}
		else{
			m= p.matcher(dirOrFile.getName());
			if (m.matches()) {
				files=new File[1];
				files[0]= dirOrFile;
			}
		}
		if (files != null) Arrays.sort(files);
		return files;
	}
	
	/**Extracts files or directories in the dir that begin with the prefix, not recursive. Returns an empty File[] if nothing found*/
	public static File[] extractFilesPrefix(File dir, String prefix){
		ArrayList<File> toReturn = new ArrayList<File>();
		for (File f: dir.listFiles()) {
			if (f.getName().startsWith(prefix)) toReturn.add(f);
		}
		File[] matches = new File[toReturn.size()];
		toReturn.toArray(matches);
		return matches;
	}
	
	public static void deleteDirectoryViaCmdLine(File dir){
		try {
			executeCommandLine(new String[]{"rm","-rf",dir.getCanonicalPath()});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**Writes a String to disk. */
	public static boolean writeString(String data, File file) {
		try {
			PrintWriter out = new PrintWriter(new FileWriter(file));
			out.print(data);
			out.close();
			return true;
		} catch (IOException e) {
			System.out.println("Problem writing String to disk!");
			e.printStackTrace();
			return false;
		}
	}
	
	/**Creates symbolicLinks in the destinationDir*/
	public static void createSymbolicLinks(File[] filesToLink, File destinationDir) throws IOException {
		//remove any linked files
		File f = destinationDir.getCanonicalFile();
		for (File fn: filesToLink) new File(f, fn.getName()).delete();

		//soft link in the new ones
		for (File fn: filesToLink) {
			Path real = fn.toPath();
			Path link = new File(f, fn.getName()).toPath();
			Files.createSymbolicLink(link, real);
		}
	}
	
	/**Creates symbolicLinks in the destinationDir*/
	public static void createSymbolicLinks(ArrayList<File>filesToLink, File destinationDir) throws IOException {
		File[] toLink = new File[filesToLink.size()];
		filesToLink.toArray(toLink);
		createSymbolicLinks(toLink, destinationDir);
	}
	
	/**Returns a nicely formated time (15May2004).
	 * (Note, all I can say is that the GC DateFormat Date classes are so convoluted as to be utterly useless. Shame!)*/
	public static String getDateNoSpaces(){
		String[] months = {"Jan","Feb","Mar","Apr","May","June","July", "Aug","Sept","Oct","Nov","Dec"};
		GregorianCalendar c = new GregorianCalendar();
		return c.get(Calendar.DAY_OF_MONTH)+months[c.get(Calendar.MONTH)]+ c.get(Calendar.YEAR);
	}
	
	public static void pl(Object o) {
		System.out.println(o);
	}
	
	public static void el(Object o) {
		System.err.println(o);
	}
	
	/**Prints message to screen, then exits.*/
	public static void printErrAndExit (String message){
		System.err.println (message);
		System.exit(1);
	}
	
	/**Returns a gz zip or straight file reader on the file based on it's extension.
	 * @author davidnix*/
	public static BufferedReader fetchBufferedReader( File txtFile) throws IOException{
		BufferedReader in;
		String name = txtFile.getName().toLowerCase();
		if (name.endsWith(".zip")) {
			ZipFile zf = new ZipFile(txtFile);
			ZipEntry ze = (ZipEntry) zf.entries().nextElement();
			in = new BufferedReader(new InputStreamReader(zf.getInputStream(ze)));
		}
		else if (name.endsWith(".gz")) {
			in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(txtFile))));
		}
		else in = new BufferedReader (new FileReader (txtFile));
		return in;
	}
	
	/**Loads a file's lines into a hash splitting on tab, using the designated keys. Returns null if a problem.*/
	public static LinkedHashMap<String,String> loadFileIntoHash(File file, int keyIndex, int valueIndex){
		LinkedHashMap<String,String> names = new LinkedHashMap<String,String>(1000);
		BufferedReader in = null;
		try{
			in = fetchBufferedReader(file);
			String line;
			String[] fields;
			int maxIndex = keyIndex;
			if (valueIndex> maxIndex) maxIndex = valueIndex;
			while ((line = in.readLine())!=null){
				line = line.trim();
				if (line.length()==0 || line.startsWith("#")) continue;
				fields = TAB.split(line);
				//skip lines missing requested indexs
				if (fields.length> maxIndex) names.put(fields[keyIndex].trim(), fields[valueIndex].trim());
			}
			in.close();
		}catch(Exception e){
			e.printStackTrace();
			names = null;
		} finally {
			closeNoException(in);
		}
		return names;
	}
	
	public static void closeNoException(BufferedReader in) {
		try {
			in.close();
		} catch (IOException e) {
		}
	}

	/**Returns a hash of the directory name and its File object.*/
	public static HashMap<String, File> extractDirectories(File directory){
		File[] fileNames = directory.listFiles();
		HashMap<String, File> toReturn = new HashMap<String, File>();
		for (int i=0; i< fileNames.length; i++)  {
			if (fileNames[i].isDirectory() == false) continue;
			toReturn.put(fileNames[i].getName(), fileNames[i]);
		}
		return toReturn;
	}
	
	/**Returns a String separated by the separator for each element.*/
	public static String stringArrayToString(String[] s, String separator){
		if (s==null) return "";
		int len = s.length;
		if (len==1) return s[0];
		if (len==0) return "";
		StringBuffer sb = new StringBuffer(s[0]);
		for (int i=1; i<len; i++){
			sb.append(separator);
			sb.append(s[i]);
		}
		return sb.toString();
	}
	
	/**Returns a String separated by the separator for each element.*/
	public static String stringHashToString(HashSet<String> s, String separator){
		if (s==null) return "";
		int len = s.size();
		if (len==1) return s.iterator().next();
		if (len==0) return "";
		Iterator<String> it = s.iterator();
		StringBuffer sb = new StringBuffer(it.next());
		while (it.hasNext()){
			sb.append(separator);
			sb.append(it.next());
		}
		return sb.toString();
	}
	
	/**Given a directory, returns a HashMap<String, File> of the containing directories' names and a File obj for the directory. */
	public static HashMap<String, File> fetchNamesAndDirectories(File directory){
		HashMap<String, File> nameFile = new HashMap<String, File>();
		File[] files = Util.extractFiles(directory);	
		for (int i=0; i< files.length; i++){
			if (files[i].isDirectory()) nameFile.put(files[i].getName(), files[i]);
		}
		return nameFile;
	}
	
	/**Given a directory, returns a HashMap<String, File> of the containing files names and a File obj for the directory. */
	public static HashMap<String, File> fetchNamesAndFiles(File directory){
		HashMap<String, File> nameFile = new HashMap<String, File>();
		File[] files = extractFiles(directory);	
		for (int i=0; i< files.length; i++){
			if (files[i].isDirectory() == false) nameFile.put(files[i].getName(), files[i]);
		}
		return nameFile;
	}
	
	/**Extracts the full path file names of all the files and directories in a given directory. If a file is given it is
	 * returned as the File[0].
	 * Skips files starting with a '.'*/
	public static File[] extractFiles(File directory){
		try{
			directory = directory.getCanonicalFile();
			File[] files = null;	
			String[] fileNames;
			if (directory.isDirectory()){
				fileNames = directory.list();
				if (fileNames != null) {
					int num = fileNames.length;
					ArrayList<File> al = new ArrayList<File>();

					String path = directory.getCanonicalPath();
					for (int i=0; i< num; i++)  {
						if (fileNames[i].startsWith(".") == false) al.add(new File(path, fileNames[i])); 
					}
					//convert arraylist to file[]
					if (al.size() != 0){
						files = new File[al.size()];
						al.toArray(files);
					}
				}
			}
			if (files == null){
				files = new File[1];
				files[0] = directory;
			}
			Arrays.sort(files);
			return files;

		}catch(IOException e){
			System.out.println("Problem extractFiles() "+directory);
			e.printStackTrace();
			return null;
		}
	}

	
	/**Returns directories or null if none found. Not recursive.
	 * Skips those beginning with a period.*/
	public static File[] extractOnlyDirectories(File directory){
		if (directory.isDirectory() == false) return null;
		File[] fileNames = directory.listFiles();
		ArrayList<File> al = new ArrayList<File>();
		Pattern pat = Pattern.compile("^\\w+.*");
		Matcher mat; 
		for (int i=0; i< fileNames.length; i++)  {
			if (fileNames[i].isDirectory() == false) continue;
			mat = pat.matcher(fileNames[i].getName());
			if (mat.matches()) al.add(fileNames[i]);
		}
		//convert arraylist to file[]
		if (al.size() != 0){
			File[] files = new File[al.size()];
			al.toArray(files);
			Arrays.sort(files);
			return files;
		}
		else return null;
	}
	/**Merges all files in File[][] to a File[].*/
	public static File[] collapseFileArray(File[][] f) {
		ArrayList<File> al = new ArrayList<File>();
		for (int i=0; i< f.length; i++){
			if (f[i] != null){
				for (int j=0; j< f[i].length; j++){
					al.add(f[i][j]);
				}
			}
		}
		File[] files = new File[al.size()];
		al.toArray(files);
		return files;
	}

}
