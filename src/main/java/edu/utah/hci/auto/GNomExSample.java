package edu.utah.hci.auto;

public class GNomExSample {
	
	//fields
	private String requestId;
	private String sampleId;
	private String species;
	private String requestLastModifiedDate;
	private String requestCreationDate;
	private String oraSpecies;
	private boolean oraCompress = true;
	
	public GNomExSample(String[] results) {
		requestId = results[0];
		sampleId = results[1];
		species = results[2];
		requestCreationDate = results[3];
		requestLastModifiedDate = results[4];
		if (results[5].equals("N")) oraCompress = false;
	}
	
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(requestId); sb.append("\t");
		sb.append(sampleId); sb.append("\t");
		sb.append(species); sb.append("\t");
		sb.append(oraCompress); sb.append("\t");
		sb.append(oraSpecies); sb.append("\t");
		sb.append(requestCreationDate);
		return sb.toString();
	}

	public String getRequestId() {
		return requestId;
	}

	public String getSampleId() {
		return sampleId;
	}

	public String getSpecies() {
		return species;
	}

	public String getRequestCreationDate() {
		return requestCreationDate;
	}

	public void setOraSpecies(String oraSpecies) {
		this.oraSpecies = oraSpecies;
		
	}

}
