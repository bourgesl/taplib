package tap.upload;

/*
 * This file is part of TAPLibrary.
 * 
 * TAPLibrary is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * TAPLibrary is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with TAPLibrary.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright 2012-2014 - UDS/Centre de Données astronomiques de Strasbourg (CDS),
 *                       Astronomisches Rechen Institut (ARI)
 */

import java.io.IOException;
import java.io.InputStream;

import tap.ServiceConnection;
import tap.ServiceConnection.LimitUnit;
import tap.TAPException;
import tap.data.DataReadException;
import tap.data.LimitedTableIterator;
import tap.data.TableIterator;
import tap.data.VOTableIterator;
import tap.db.DBConnection;
import tap.metadata.TAPColumn;
import tap.metadata.TAPMetadata;
import tap.metadata.TAPMetadata.STDSchema;
import tap.metadata.TAPSchema;
import tap.metadata.TAPTable;
import tap.parameters.DALIUpload;
import uws.UWSException;
import uws.service.file.UnsupportedURIProtocolException;

import com.oreilly.servlet.multipart.ExceededSizeException;

/**
 * <p>Let create properly given VOTable inputs in the "database".</p>
 * 
 * <p>
 * 	This class manages particularly the upload limit in rows and in bytes by creating a {@link LimitedTableIterator}
 * 	with a {@link VOTableIterator}.
 * </p>
 * 
 * @author Gr&eacute;gory Mantelet (CDS;ARI)
 * @version 2.0 (11/2014)
 * 
 * @see LimitedTableIterator
 * @see VOTableIterator
 */
public class Uploader {
	/** Specification of the TAP service. */
	protected final ServiceConnection service;
	/** Connection to the "database" (which lets upload the content of any given VOTable). */
	protected final DBConnection dbConn;
	/** Description of the TAP_UPLOAD schema to use.
	 * @since 2.0 */
	protected final TAPSchema uploadSchema;
	/** Type of limit to set: ROWS or BYTES. <i>MAY be NULL ; if NULL, no limit will be set.</i> */
	protected final LimitUnit limitUnit;
	/** Limit on the number of rows or bytes (depending of {@link #limitUnit}) allowed to be uploaded in once (whatever is the number of tables). */
	protected final int limit;

	/** Number of rows already loaded. */
	protected int nbRows = 0;

	/**
	 * Build an {@link Uploader} object.
	 * 
	 * @param service	Specification of the TAP service using this uploader.
	 * @param dbConn	A valid (open) connection to the "database".
	 * 
	 * @throws TAPException	If any error occurs while building this {@link Uploader}.
	 */
	public Uploader(final ServiceConnection service, final DBConnection dbConn) throws TAPException{
		this(service, dbConn, null);
	}

	/**
	 * Build an {@link Uploader} object.
	 * 
	 * @param service	Specification of the TAP service using this uploader.
	 * @param dbConn	A valid (open) connection to the "database".
	 * 
	 * @throws TAPException	If any error occurs while building this {@link Uploader}.
	 * 
	 * @since 2.0
	 */
	public Uploader(final ServiceConnection service, final DBConnection dbConn, final TAPSchema uplSchema) throws TAPException{
		// NULL tests:
		if (service == null)
			throw new NullPointerException("The given ServiceConnection is NULL !");
		if (dbConn == null)
			throw new NullPointerException("The given DBConnection is NULL !");

		// Set the service and database connections:
		this.service = service;
		this.dbConn = dbConn;

		// Set the given upload schema:
		if (uplSchema != null){
			if (!uplSchema.getADQLName().equalsIgnoreCase(TAPMetadata.STDSchema.UPLOADSCHEMA.label))
				throw new TAPException("Incorrect upload schema! Its ADQL name MUST be \"" + TAPMetadata.STDSchema.UPLOADSCHEMA.label + "\" ; here is is \"" + uplSchema.getADQLName() + "\".", UWSException.INTERNAL_SERVER_ERROR);
			else
				this.uploadSchema = uplSchema;
		}
		// ...or the default one:
		else
			this.uploadSchema = new TAPSchema(TAPMetadata.STDSchema.UPLOADSCHEMA.label, "Schema for tables uploaded by users.");

		// Ensure UPLOAD is allowed by the TAP service specification...
		if (this.service.uploadEnabled()){
			// ...and set the rows or bytes limit:
			if (this.service.getUploadLimitType()[1] != null && this.service.getUploadLimit()[1] > 0){
				limit = this.service.getUploadLimit()[1];
				limitUnit = this.service.getUploadLimitType()[1];
			}else{
				limit = -1;
				limitUnit = null;
			}
		}else
			throw new TAPException("Upload aborted: this functionality is disabled in this TAP service!");
	}

	/**
	 * <p>Upload all the given VOTable inputs.</p>
	 * 
	 * <p><i>Note:
	 * 	The {@link TAPTable} objects representing the uploaded tables will be associated with the TAP_UPLOAD schema specified at the creation of this {@link Uploader}.
	 * 	If no such schema was specified, a default one (whose DB name will be equals to the ADQL name, that's to say {@link STDSchema#UPLOADSCHEMA})
	 * 	is created, will be associated with the uploaded tables and will be returned by this function.
	 * </i></p>
	 * 
	 * @param uploads	Array of tables to upload.
	 * 
	 * @return	A {@link TAPSchema} containing the list and the description of all uploaded tables.
	 * 
	 * @throws TAPException	If any error occurs while reading the VOTable inputs or while uploading the table into the "database".
	 * 
	 * @see DBConnection#addUploadedTable(TAPTable, tap.data.TableIterator)
	 */
	public TAPSchema upload(final DALIUpload[] uploads) throws TAPException{
		InputStream votable = null;
		String tableName = null;
		try{
			// Iterate over the full list of uploaded tables:
			for(DALIUpload upl : uploads){
				tableName = upl.label;

				// Open a stream toward the VOTable:
				votable = upl.open();

				// Start reading the VOTable (with the identified limit, if any):
				TableIterator dataIt = new LimitedTableIterator(VOTableIterator.class, votable, limitUnit, limit);

				// Define the table to upload:
				TAPColumn[] columns = dataIt.getMetadata();
				TAPTable table = new TAPTable(tableName);
				table.setDBName(tableName + "_" + System.currentTimeMillis());
				for(TAPColumn col : columns)
					table.addColumn(col);

				// Add the table to the TAP_UPLOAD schema:
				uploadSchema.addTable(table);

				// Create and fill the corresponding table in the database:
				dbConn.addUploadedTable(table, dataIt);

				// Close the VOTable stream:
				votable.close();
				votable = null;
			}
		}catch(DataReadException dre){
			if (dre.getCause() instanceof ExceededSizeException)
				throw dre;
			else
				throw new TAPException("Error while reading the VOTable \"" + tableName + "\": " + dre.getMessage(), dre, UWSException.BAD_REQUEST);
		}catch(IOException ioe){
			throw new TAPException("IO error while reading the VOTable of \"" + tableName + "\"!", ioe);
		}catch(UnsupportedURIProtocolException e){
			throw new TAPException("URI error while trying to open the VOTable of \"" + tableName + "\"!", e);
		}finally{
			try{
				if (votable != null)
					votable.close();
			}catch(IOException ioe){
				;
			}
		}

		// Return the TAP_UPLOAD schema (containing just the description of the uploaded tables):
		return uploadSchema;
	}

}
