AutoAnalysis orchestrates auto analysis of sequencing files in GNomEx Experiment using two different daemons running on HCI or on CHPC.

GNomExAutoAnalysis - Sets up primary AutoAnalysis folders for GNomEx Experiment Requests (ER)
1) Interrogates the GNomEx sql server for Align and QC analysis requests from client in the "Bioinformatics" tab of an ER
2) Runs several checks to see if the ER can be serviced: age of the Fastq/ folder files, presence of an md5 checksum file, available organism - library prep matched analysis workflow
3) Creates individual sample job folders with links to the fastq. Adds a RUNME file containing paths to the workflow docs to copy into the job folders and execute on CHPC's redwood cluster.
4) Waits for all of an ER sample job folder contents to be deleted and replaced with the analysis results from CHPC and a COMPLETE file
3) Runs MultiQC on the job folders for each ER. Runs the USeq JobCleaner to remove .tbi,.crai,.bai and COMPLETE files as well as zip compress any Logs or RunScripts folders. 
7) Emails the submitter of the completed AutoAnalysis for their ER
8) Emails the admin of any issues encountered and a "I'm alive" message every 24hrs.
    Run on hci-deadhorse.hci.utah.edu


