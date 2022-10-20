package com.bosch.cc.as.soda.datacollector.scminterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bosch.cc.as.soda.datacollector.common.DateUtil;
import com.bosch.cc.as.soda.datacollector.common.EnvUtil;
import com.bosch.cc.as.soda.datacollector.common.FileUtil;
import com.bosch.cc.as.soda.datacollector.common.SodaConstants;
import com.bosch.cc.as.soda.datacollector.factory.FactorySoDA;
import com.bosch.cc.as.soda.datacollector.logger.ILogger;
import com.bosch.cc.as.soda.datacollector.logger.MessageSeverity;
import com.bosch.cc.as.soda.datacollector.model.DeliveryWorkitemData;
import com.bosch.cc.as.soda.datacollector.model.ScmComponentDetail;
import com.ibm.team.aspice.common.utils.Utils;
import com.ibm.team.filesystem.client.FileSystemCore;
import com.ibm.team.filesystem.client.ILoadRuleFactory;
import com.ibm.team.filesystem.client.ILocation;
import com.ibm.team.filesystem.client.IOperationFactory;
import com.ibm.team.filesystem.client.IRelativeLocation;
import com.ibm.team.filesystem.client.ISandbox;
import com.ibm.team.filesystem.client.ISharingManager;
import com.ibm.team.filesystem.client.internal.PathLocation;
import com.ibm.team.filesystem.client.internal.RelativeLocation;
import com.ibm.team.filesystem.client.operations.ILoadOperation;
import com.ibm.team.filesystem.client.operations.ILoadRule2;
import com.ibm.team.filesystem.client.operations.LoadDilemmaHandler;
import com.ibm.team.filesystem.common.IFileItemHandle;
import com.ibm.team.process.client.IProcessClientService;
import com.ibm.team.process.common.IDevelopmentLine;
import com.ibm.team.process.common.IDevelopmentLineHandle;
import com.ibm.team.process.common.IIteration;
import com.ibm.team.process.common.IIterationHandle;
import com.ibm.team.process.common.IProjectArea;
import com.ibm.team.process.common.IProjectAreaHandle;
import com.ibm.team.repository.client.IItemManager;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.client.TeamPlatform;
import com.ibm.team.repository.common.IItemHandle;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.common.UUID;
import com.ibm.team.scm.client.IConfiguration;
import com.ibm.team.scm.client.IVersionableManager;
import com.ibm.team.scm.client.IWorkspaceConnection;
import com.ibm.team.scm.client.IWorkspaceManager;
import com.ibm.team.scm.client.SCMPlatform;
import com.ibm.team.scm.common.IBaseline;
import com.ibm.team.scm.common.IBaselineHandle;
import com.ibm.team.scm.common.IBaselineSet;
import com.ibm.team.scm.common.IBaselineSetHandle;
import com.ibm.team.scm.common.IComponent;
import com.ibm.team.scm.common.IComponentHandle;
import com.ibm.team.scm.common.IFolderHandle;
import com.ibm.team.scm.common.IVersionableHandle;
import com.ibm.team.scm.common.IWorkspace;
import com.ibm.team.scm.common.IWorkspaceHandle;
import com.ibm.team.scm.common.dto.IAncestorReport;
import com.ibm.team.scm.common.dto.INameItemPair;
import com.ibm.team.workitem.client.IAuditableClient;
import com.ibm.team.workitem.client.IQueryClient;
import com.ibm.team.workitem.client.IWorkItemClient;
import com.ibm.team.workitem.common.IWorkItemCommon;
import com.ibm.team.workitem.common.expression.AttributeExpression;
import com.ibm.team.workitem.common.expression.Expression;
import com.ibm.team.workitem.common.expression.IQueryableAttribute;
import com.ibm.team.workitem.common.expression.QueryableAttributes;
import com.ibm.team.workitem.common.expression.Term;
import com.ibm.team.workitem.common.model.AttributeOperation;
import com.ibm.team.workitem.common.model.IAttribute;
import com.ibm.team.workitem.common.model.IAttributeHandle;
import com.ibm.team.workitem.common.model.IEnumeration;
import com.ibm.team.workitem.common.model.ILiteral;
import com.ibm.team.workitem.common.model.IWorkItem;
import com.ibm.team.workitem.common.model.Identifier;
import com.ibm.team.workitem.common.model.ItemProfile;
import com.ibm.team.workitem.common.query.IQueryResult;
import com.ibm.team.workitem.common.query.IResolvedResult;

public class ScmInterfaceRTC implements IScmInterface {

	private static final String FAILED_MSG = " failed. This Workitem doesn't considered for processing";

	public class ComponentDetailsProcessor implements Runnable {
		IComponentHandle componentHandle;
		IWorkspaceConnection wsConnection = null;
		List<IVersionableHandle> rawFilesList = new ArrayList<>();
		List<ScmComponentDetail> componentDetails = null;
		private IBaseline baseline;

		public ComponentDetailsProcessor(
				final Entry<IComponentHandle, IBaseline> compBaseline,
				final IWorkspaceConnection wsConnection,
				final List<ScmComponentDetail> componentDetails) {
			this.componentHandle = compBaseline.getKey();
			this.baseline = compBaseline.getValue();
			this.wsConnection = wsConnection;
			this.componentDetails = componentDetails;
		}

		@Override
		public void run() {
			IComponent component;
			try {
				component = (IComponent) ScmInterfaceRTCSoda.this.teamRepository
						.itemManager().fetchCompleteItem(this.componentHandle,
								IItemManager.DEFAULT,
								ScmInterfaceRTCSoda.this.monitor);
				String name = component.getName();
				ScmComponentDetail scmComponent = new ScmComponentDetail();
				scmComponent.setName(name);
				scmComponent.setBaseline(this.baseline);
				IConfiguration configuration = this.wsConnection
						.configuration(this.componentHandle);
				IFolderHandle parent = configuration
						.rootFolderHandle(ScmInterfaceRTCSoda.this.monitor);
				ScmInterfaceRTCSoda.this.logger.log(MessageSeverity.INFO,
						"Processing details of component..." + name);

				getRawFilesList(ScmInterfaceRTCSoda.this.teamRepository,
						this.wsConnection, component, parent,
						ScmInterfaceRTCSoda.this.monitor);
				ScmInterfaceRTCSoda.this.logger.log(MessageSeverity.INFO,
						"Found " + this.rawFilesList.size() + " files under "
								+ component.getName());
				this.rawFilesList.parallelStream().distinct()
						.filter(v -> v != null).forEach(handle -> {
							String path = getFilePath(configuration, handle);
							scmComponent.addContent(path);
						});
				this.componentDetails.add(scmComponent);
			} catch (TeamRepositoryException e) {
				ScmInterfaceRTCSoda.this.logger.log(MessageSeverity.ERROR,
						"Component cannot be fetched from "
								+ this.componentHandle.getItemId() + ": "
								+ e.getMessage());
			}
		}

		private void getRawFilesList(final ITeamRepository repo,
				final IWorkspaceConnection wsConn, final IComponent component,
				final IFolderHandle parent, final IProgressMonitor monitor)
				throws TeamRepositoryException {
			IConfiguration configuration = wsConn.configuration(
					(IComponentHandle) component.getItemHandle());
			Map<String, IVersionableHandle> children = null;
			children = parent == null
					? configuration.childEntriesForRoot(null)
					: configuration.childEntries(parent, null);
			if (children != null) {
				for (Entry<String, IVersionableHandle> entry : children
						.entrySet()) {
					IVersionableHandle item = children.get(entry.getKey());
					if (item instanceof IFolderHandle) {
						getRawFilesList(repo, wsConn, component,
								(IFolderHandle) item, monitor);
					} else {
						if (item instanceof IFileItemHandle
								&& !this.rawFilesList.contains(item)) {
							this.rawFilesList.add(item);
						}
					}
				}
			}
		}

		private String getFilePath(final IConfiguration configuration,
				final IVersionableHandle item) {
			List<IVersionableHandle> handles = new ArrayList<>();
			handles.add(item);

			List<IAncestorReport> ancestorReports;
			StringBuilder filePath = new StringBuilder("");
			try {
				ancestorReports = configuration.locateAncestors(handles,
						ScmInterfaceRTCSoda.this.monitor);
				// Lets get the path of the first one, corresponding to the
				// first versionable
				IAncestorReport iAncestorReport = ancestorReports.get(0);
				List<INameItemPair> reportList = iAncestorReport
						.getNameItemPairs();

				for (INameItemPair iNameItemPair : reportList) {
					String temp = iNameItemPair.getName();
					if (temp != null) {
						filePath.append("\\").append(temp);
					}
				}
			} catch (TeamRepositoryException e) {
				ScmInterfaceRTCSoda.this.logger.log(MessageSeverity.ERROR,
						"File path cannot be resolved " + e.getMessage());
			}
			return filePath.toString();
		}
	}

	public class BaselineProcessor implements Runnable {
		private IBaselineHandle baselineHandle;
		private IScmObject object;

		public BaselineProcessor(final IBaselineHandle baselineHandle,
				final IScmObject object) {
			this.baselineHandle = baselineHandle;
			this.object = object;
		}

		@Override
		public void run() {
			try {
				IBaseline baseline = (IBaseline) ScmInterfaceRTCSoda.this.teamRepository
						.itemManager().fetchCompleteItem(this.baselineHandle,
								IItemManager.DEFAULT,
								ScmInterfaceRTCSoda.this.monitor);
				this.object.addComponentBaselineHandle(
						new ScmComponentHandle(baseline.getComponent()),
						baseline);
			} catch (TeamRepositoryException e) {
				ScmInterfaceRTCSoda.this.logger.log(MessageSeverity.ERROR,
						"Baseline cannot be fetched from "
								+ this.baselineHandle.getItemId() + ": "
								+ e.getMessage());
			}
		}
	}

	public class LoadProblemHandler extends LoadDilemmaHandler {
	}

	private ILogger logger = null;
	private ITeamRepository teamRepository = null;
	private IProgressMonitor monitor = null;
	private IWorkspaceManager workspaceManager = null;
	IVersionableManager versionableManager = null;

	public static IScmInterfaceSoda getInstance() {
		return new ScmInterfaceRTCSoda();
	}

	private ScmInterfaceRTCSoda() {
	}

	/**
	 * Login to RTC.
	 * 
	 * @param url
	 * @param user
	 * @param password
	 * @return exit status
	 */
	@Override
	public boolean login(final String url, final String user,
			final String password) {
		boolean bRet = true;
		this.teamRepository = TeamPlatform.getTeamRepositoryService()
				.getTeamRepository(url);
		this.teamRepository
				.registerLoginHandler(new RTCLoginHandler(user, password));
		try {
			this.monitor = new NullProgressMonitor();
			this.teamRepository.login(this.monitor);
			this.workspaceManager = SCMPlatform
					.getWorkspaceManager(this.teamRepository);
			this.versionableManager = this.workspaceManager
					.versionableManager();
		} catch (TeamRepositoryException e) {
			this.logger.log(MessageSeverity.ERROR,
					e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
			bRet = false;
		}
		return bRet;
	}

	@Override
	public boolean logout() {
		boolean bRet = true;
		if (null != this.teamRepository) {
			this.teamRepository.logout();
		} else {
			bRet = false;
		}
		return bRet;
	}

	@Override
	public void open() {
		TeamPlatform.startup();
	}

	@Override
	public void close() {
		TeamPlatform.shutdown();
	}

	@Override
	public void setLogger(final ILogger logger) {
		this.logger = logger;
	}

	/**
	 * Find the delivery workitems and its attributes for a given project area,
	 * timeline and iteration
	 * 
	 * @param projectAreaName
	 * @param timeline
	 * @param iterationName
	 * @param deliveryWorkitemDetails
	 * @see deliveryWorkitemDetails
	 * @return exit status
	 */
	@Override
	public boolean findDeliveryWorkitemDetails(final String projectAreaName,
			final String timeline, final String iterationName,
			final List<DeliveryWorkitemData> deliveryWorkitemDetails) {
		this.logger.log(MessageSeverity.INFO,
				"Finding project area " + projectAreaName);
		boolean status = true;
		IProcessClientService processClient = (IProcessClientService) this.teamRepository
				.getClientLibrary(IProcessClientService.class);
		URI uri = URI.create(projectAreaName.replaceAll(" ", "%20"));
		try {
			IProjectArea projectArea = (IProjectArea) processClient
					.findProcessArea(uri, null, null);
			if (projectArea != null) {
				this.logger.log(MessageSeverity.INFO, "Project area found");
				IAuditableClient auditableClient = (IAuditableClient) this.teamRepository
						.getClientLibrary(IAuditableClient.class);
				IDevelopmentLineHandle[] developmentLineHandles = projectArea
						.getDevelopmentLines();
				this.logger.log(MessageSeverity.INFO,
						"Finding Timeline from project area "
								+ projectAreaName);
				if (developmentLineHandles.length > 1) {

					IIterationHandle[] iterations = null;
					for (IDevelopmentLineHandle developmentLineHandle : developmentLineHandles) {
						IDevelopmentLine developmentLine = (IDevelopmentLine) this.teamRepository
								.itemManager()
								.fetchCompleteItem(developmentLineHandle,
										IItemManager.DEFAULT, this.monitor);

						this.logger.log(MessageSeverity.INFO,
								"Timeline: " + developmentLine.getName());
						if (developmentLine.getName().trim()
								.equalsIgnoreCase(timeline.trim())) {
							this.logger.log(MessageSeverity.INFO,
									"Timeline found");
							this.logger.log(MessageSeverity.INFO,
									"Finding Iteration from Timeline "
											+ timeline);
							iterations = developmentLine.getIterations();
							break;
						}
					}

					if (iterations != null) {
						IIteration iteration = findIteration(iterations,
								auditableClient, iterationName);
						if (null != iteration) {
							this.logger.log(MessageSeverity.INFO,
									"Iteration found");
							status = findDeliveryWIandFetchDetails(iteration,
									projectArea, timeline.trim(),
									deliveryWorkitemDetails);
						} else {
							this.logger.log(MessageSeverity.INFO,
									"Iteration specified doesn't exist");
							status = false;
						}
					} else {
						this.logger.log(MessageSeverity.INFO,
								"Timeline specified doesn't exist");
						status = false;
					}
				} else {
					this.logger.log(MessageSeverity.ERROR,
							"0 Timeline found under the project area "
									+ projectAreaName);
					status = false;
				}
			} else {
				this.logger.log(MessageSeverity.INFO,
						"Project area specified doesn't exist");
				status = false;
			}
		} catch (TeamRepositoryException | NullPointerException e) {
			this.logger.log(MessageSeverity.ERROR,
					"Error while processing delivery workitem details: "
							+ e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
			status = false;
		}
		return status;
	}

	/**
	 * Find the iteration for given timeline.
	 * 
	 * @param iterations
	 * @param auditableClient
	 * @param iterationName
	 * @return iteration
	 */
	private IIteration findIteration(final IIterationHandle[] iterations,
			final IAuditableClient auditableClient,
			final String iterationName) {
		IIteration iteration = null;
		try {
			for (IIterationHandle iIterationHandle : iterations) {
				iteration = auditableClient.resolveAuditable(iIterationHandle,
						ItemProfile.ITERATION_DEFAULT, this.monitor);
				this.logger.log(MessageSeverity.INFO,
						"	Iteration: " + iteration.getName().trim());
				if (iteration.getName().trim()
						.equalsIgnoreCase(iterationName.trim())) {
					break;
				} else {
					iteration = findIteration(iteration.getChildren(),
							auditableClient, iterationName);
					if (null != iteration) {
						break;
					}
				}
			}
		} catch (TeamRepositoryException | NullPointerException e) {
			this.logger.log(MessageSeverity.ERROR,
					"Error while processing Iteration details: "
							+ e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
		}
		return iteration;
	}

	/**
	 * Find the delivery workitems and its attributes.
	 * 
	 * @param iterations
	 * @param projectArea
	 * @param timeLine
	 * @param deliveryWorkitemDetails
	 * @return exit status
	 */
	private boolean findDeliveryWIandFetchDetails(final IIteration iteration,
			final IProjectArea projectArea, final String timeLine,
			final List<DeliveryWorkitemData> deliveryWorkitemDetails) {
		boolean status = true;
		try {
			this.logger.log(MessageSeverity.INFO,
					"Finding delivery workitem details from iteration "
							+ iteration.getName());
			IAuditableClient auditableClient = (IAuditableClient) this.teamRepository
					.getClientLibrary(IAuditableClient.class);
			IQueryableAttribute plannedFor = QueryableAttributes
					.getFactory(IWorkItem.ITEM_TYPE).findAttribute(projectArea,
							IWorkItem.TARGET_PROPERTY, auditableClient,
							this.monitor);
			Expression isPlannedFor = new AttributeExpression(plannedFor,
					AttributeOperation.EQUALS, iteration);

			IQueryableAttribute typeAttibute = QueryableAttributes
					.getFactory(IWorkItem.ITEM_TYPE).findAttribute(projectArea,
							IWorkItem.TYPE_PROPERTY, auditableClient,
							this.monitor);
			Expression deliverytypeExp = new AttributeExpression(typeAttibute,
					AttributeOperation.EQUALS,
					"com.bosch.rtc.configuration.workitemtype.type.delivery");

			Term term = new Term(Term.Operator.AND);
			term.add(isPlannedFor);
			term.add(deliverytypeExp);

			IQueryClient queryClient = (IQueryClient) this.teamRepository
					.getClientLibrary(IQueryClient.class);
			IQueryResult<IResolvedResult<IWorkItem>> result = queryClient
					.getResolvedExpressionResults(projectArea, term,
							IWorkItem.FULL_PROFILE);

			if (result.hasNext(this.monitor)) {
				while (result.hasNext(this.monitor)) {
					DeliveryWorkitemData deliveryWIData = new DeliveryWorkitemData();
					deliveryWIData.setIteration(iteration.getName());
					deliveryWIData.setTimeLine(timeLine);
					IResolvedResult<IWorkItem> resolvedResult = result
							.next(this.monitor);
					IWorkItem workitem = resolvedResult.getItem();
					this.logger.log(MessageSeverity.INFO,
							"Delivery workitem found " + workitem.getId());
					status = findDeliveryWIAttributeData(workitem, projectArea,
							deliveryWIData);

					if (status) {
						status = fetchStreamUUIDfromSnapshot(deliveryWIData);
						if (status) {
							deliveryWIData.setWorkitem(workitem.getId());
							this.logger.log(MessageSeverity.INFO,
									deliveryWIData.toString());
							deliveryWorkitemDetails.add(deliveryWIData);
						} else {
							this.logger.log(MessageSeverity.ERROR,
									"Processing of getting stream uuid from workitem "
											+ workitem.getId() + FAILED_MSG);
						}
					} else {
						this.logger.log(MessageSeverity.ERROR,
								"Processing of attribute data from workitem "
										+ workitem.getId() + FAILED_MSG);
					}
				}
			} else {
				this.logger.log(MessageSeverity.WARNING,
						"No Delivery workitems found");
			}
		} catch (TeamRepositoryException | NullPointerException e) {
			this.logger.log(MessageSeverity.ERROR,
					"Error while processing delivery workitem: "
							+ e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
			status = false;
		}
		return status;
	}

	/**
	 * Find the stream uuid from snapshot uuid
	 * 
	 * @param deliveryWIData
	 * @see deliveryWIData
	 * @return exit status
	 */
	private boolean fetchStreamUUIDfromSnapshot(
			final DeliveryWorkitemData deliveryWIData) {
		boolean status = true;
		try {
			if (deliveryWIData.getSnapshotUUID() != null) {
				this.logger.log(MessageSeverity.INFO,
						"Fetching stream uuid from snapshot "
								+ deliveryWIData.getSnapshotUUID());

				IBaselineSetHandle snapBaselineHandle = (IBaselineSetHandle) IBaselineSet.ITEM_TYPE
						.createItemHandle(
								UUID.valueOf(deliveryWIData.getSnapshotUUID()),
								null);
				IBaselineSet snapBaselineset = Utils.getCompleteItemFromServer(
						this.teamRepository, snapBaselineHandle,
						IBaselineSet.class);
				IWorkspaceHandle workspaceHandle = snapBaselineset.getOwner();
				IWorkspace stream = (IWorkspace) this.teamRepository
						.itemManager().fetchCompleteItem(workspaceHandle,
								IItemManager.DEFAULT, this.monitor);

				if (stream != null) {
					deliveryWIData
							.setStreamUUID(stream.getItemId().getUuidValue());
				}
			} else {
				this.logger.log(MessageSeverity.ERROR,
						"Snapshot uuid is empty");
				status = false;
			}
		} catch (Exception e) {
			this.logger.log(MessageSeverity.ERROR,
					"Exception occured while fetching stream uuid from snapshot "
							+ e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
			status = false;
		}
		return status;
	}

	/**
	 * Collect delivery workitems attributes
	 * 
	 * @param workitem
	 * @param projectArea
	 * @param deliveryWIData
	 * @see deliveryWIData
	 * @return exit status
	 */
	private boolean findDeliveryWIAttributeData(final IWorkItem workitem,
			final IProjectArea projectArea,
			final DeliveryWorkitemData deliveryWIData) {
		boolean status = true;
		try {
			this.logger.log(MessageSeverity.INFO,
					"Finding attributes details of delivery workitem "
							+ workitem.getId());
			IWorkItemCommon workItemCommon = (IWorkItemCommon) this.teamRepository
					.getClientLibrary(IWorkItemCommon.class);
			List<IAttribute> allAttributeHandles = workItemCommon
					.findAttributes(projectArea, this.monitor);

			for (IAttribute iAttribute2 : allAttributeHandles) {
				IAttributeHandle iAttributeHandle = iAttribute2;
				IAttribute iAttribute = (IAttribute) this.teamRepository
						.itemManager().fetchCompleteItem(iAttributeHandle,
								IItemManager.DEFAULT, this.monitor);

				if (iAttribute.getDisplayName().equalsIgnoreCase(
						SodaConstants.SNAPSHOT_BASELINE_URI)) {
					String snapshotLink = workitem.getValue(iAttribute)
							.toString();
					if (snapshotLink != null && !snapshotLink.isEmpty()) {
						deliveryWIData
								.setSnapshotUUID(snapshotLink.substring(
										snapshotLink.lastIndexOf(
												SodaConstants.SLASH) + 1,
										snapshotLink.length()));
					}
				} else if (iAttribute.getDisplayName()
						.equalsIgnoreCase(SodaConstants.ENGINEERING_LEVEL)) {
					String value = findAttributeValue(workItemCommon,
							iAttribute, workitem);
					if (value != null && !value.isEmpty()) {
						deliveryWIData.setEngineeringLevel(value);
					}
				} else if (iAttribute.getDisplayName()
						.equalsIgnoreCase(SodaConstants.MATURITY_LEVEL)) {
					String value = findAttributeValue(workItemCommon,
							iAttribute, workitem);
					if (value != null && !value.isEmpty()) {
						deliveryWIData.setMaturityLevel(value);
					}
				} else if (iAttribute.getDisplayName().equalsIgnoreCase(
						SodaConstants.RELEVENT_BUILD_VARIANTS)) {
					// special handling - some users specify multiple build
					// configs separated by
					// whitespace
					String buildVariants = workitem.getValue(iAttribute)
							.toString();
					deliveryWIData.setBuildVariants(
							removeSpecialCharacters(buildVariants));

				} else if (iAttribute.getDisplayName()
						.equalsIgnoreCase(SodaConstants.INTENDED_USE)) {
					String value = findAttributeValue(workItemCommon,
							iAttribute, workitem);
					if (value != null && !value.isEmpty()) {
						deliveryWIData.setIntendedUse(value);
					}
				}
			}

		} catch (Exception e) {
			this.logger.log(MessageSeverity.ERROR,
					"Exception occured while fetching delivery workitem attributes: "
							+ e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
			status = false;
		}
		return status;
	}

	private String removeSpecialCharacters(final String buildVariants) {
		// as of now, only whitespace characters are considered to be replaced
		// with
		// comma
		StringBuilder finalVariants = new StringBuilder("");
		String[] tokens = buildVariants.trim().split("\\s");
		int index = 0;
		for (String token : tokens) {
			if (!token.trim().isEmpty()) {
				if (0 != index) {
					finalVariants.append(",");
				}
				finalVariants.append(token.trim());
				index++;
			}
		}
		return finalVariants.toString();
	}

	/**
	 * Find attribute values.
	 * 
	 * @param workItemCommon
	 * @param iAttribute
	 * @param workitem
	 * @see deliveryWIData
	 * @return exit status
	 */
	private String findAttributeValue(final IWorkItemCommon workItemCommon,
			final IAttribute iAttribute, final IWorkItem workitem)
			throws TeamRepositoryException {
		try {
			IEnumeration<? extends ILiteral> enumeration = workItemCommon
					.resolveEnumeration(iAttribute, this.monitor);
			@SuppressWarnings({"rawtypes", "unchecked"})
			ILiteral literal = enumeration.findEnumerationLiteral(
					(Identifier) workitem.getValue(iAttribute));
			return literal.getName();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Find workitem id attribute values.
	 * 
	 * @param workitemNumber
	 * @param deliveryWorkitemDetails
	 * @see deliveryWIData
	 * @return exit status
	 */
	@Override
	public boolean findDWDetailsUsingId(final String workitemNumber,
			final String timeline, final String iteration,
			final List<DeliveryWorkitemData> deliveryWorkitemDetails) {
		boolean status = true;
		try {
			DeliveryWorkitemData deliveryWIData = new DeliveryWorkitemData();
			deliveryWIData.setTimeLine(timeline);
			deliveryWIData.setIteration(iteration);
			IWorkItemClient workItemClient = (IWorkItemClient) this.teamRepository
					.getClientLibrary(IWorkItemClient.class);
			int workitemId = Integer.parseInt(workitemNumber);
			IWorkItem workItem = workItemClient.findWorkItemById(workitemId,
					IWorkItem.SMALL_PROFILE, null);
			if (workItem != null) {
				IProjectAreaHandle projectAreaHandle = workItem
						.getProjectArea();
				if (projectAreaHandle != null) {
					IProjectArea projectArea = Utils.getCompleteItemFromServer(
							this.teamRepository, projectAreaHandle,
							IProjectArea.class);
					if (projectArea != null) {
						if (findDeliveryWIAttributeData(workItem, projectArea,
								deliveryWIData)) {
							if (fetchStreamUUIDfromSnapshot(deliveryWIData)) {
								deliveryWIData.setWorkitem(workitemId);
								this.logger.log(MessageSeverity.INFO,
										deliveryWIData.toString());
								deliveryWorkitemDetails.add(deliveryWIData);
							} else {
								this.logger.log(MessageSeverity.ERROR,
										"Processing of getting stream uuid from workitem "
												+ workitemId + FAILED_MSG);
								status = false;
							}
						} else {
							this.logger.log(MessageSeverity.ERROR,
									"Processing of attribute data from workitem "
											+ workitemId + FAILED_MSG);
							status = false;
						}
					} else {
						this.logger.log(MessageSeverity.ERROR,
								"Project area returned from api is null. This Workitem doesn't considered for processing");
						status = false;
					}
				} else {
					this.logger.log(MessageSeverity.ERROR,
							"Project area returned from api is null. This Workitem doesn't considered for processing");
					status = false;
				}
			} else {
				this.logger.log(MessageSeverity.ERROR, "Workitem id "
						+ workitemId
						+ " doesn't exists. Please provide valid workitem id ");
				status = false;
			}

		} catch (Exception e) {
			this.logger.log(MessageSeverity.ERROR,
					"Exception occured while fetching delivery workitem attributes: "
							+ e.getMessage() + SodaConstants.CLASS_NAME
							+ e.getStackTrace()[0].getClassName()
							+ SodaConstants.METHOD_NAME
							+ e.getStackTrace()[0].getMethodName());
			status = false;
		}
		return status;
	}

	@Override
	public boolean processComponentDetails(final String snapshotUUID,
			final String streamUUID, final boolean createRepoWS,
			final List<ScmComponentDetail> componentDetails) {
		boolean status = true;
		if (null != this.teamRepository && this.teamRepository.loggedIn()) {

			ScmObjectType type;
			IScmObject object;
			if (!snapshotUUID.isEmpty()) {
				type = ScmObjectType.SNAPSHOT;
				object = FactorySoDA.createScmObject(type, snapshotUUID,
						streamUUID);
			} else {
				type = ScmObjectType.STREAM;
				object = FactorySoDA.createScmObject(type, streamUUID, "");
			}

			if (null != object) {

				IScmWorkspace workspace = createRepoWS
						? createRepositoryWorkspace(object,
								SodaConstants.SODA_WORKSPACE_DESCRIPTION)
						: getWorkspace(object);

				object.setWorkspace(workspace);
				this.logger.log(MessageSeverity.INFO,
						"Fetching component list for " + object.getId()
								+ "...");
				if (null != workspace) {
					try {
						IWorkspaceConnection wsConnection = workspace
								.getWsConnection();
						if (null != wsConnection) {

							Map<IComponentHandle, IBaseline> componentBaselineHandle = new HashMap<>();

							if (ScmObjectType.STREAM == object.getType()) {
								@SuppressWarnings("unchecked")
								List<IBaseline> baselineSets = wsConnection
										.getBaselineSets(
												new NullProgressMonitor());
								baselineSets.stream().forEach(
										baseLine -> componentBaselineHandle.put(
												baseLine.getComponent(),
												baseLine));

							} else if (ScmObjectType.SNAPSHOT == object
									.getType()) {
								prepareComponentHandleFromSnapshot(workspace,
										object);
								Map<IScmComponentHandle, IBaseline> scmComponentBaselineHandleMap = object
										.getComponentBaselineHandles();
								for (Entry<IScmComponentHandle, IBaseline> iScmComponentBaselineHandle : scmComponentBaselineHandleMap
										.entrySet()) {
									componentBaselineHandle.put(
											iScmComponentBaselineHandle.getKey()
													.getComponentHandle(),
											iScmComponentBaselineHandle
													.getValue());
								}
							}

							if (!componentBaselineHandle.isEmpty()) {
								ExecutorService executorService = Executors
										.newFixedThreadPool(
												SodaConstants.COMPONENT_PROCESSING_THREAD_COUNT);
								componentBaselineHandle.entrySet()
										.forEach(comp -> {
											Runnable worker = new ComponentDetailsProcessor(
													comp, wsConnection,
													componentDetails);
											executorService.execute(worker);
										});
								executorService.shutdown();
								while (!executorService.isTerminated()) {
									//
								}

								this.logger.log(MessageSeverity.INFO,
										"Fetching component list for "
												+ object.getId()
												+ " completed; count: "
												+ componentBaselineHandle
														.size());
							}

						} else {
							this.logger.log(MessageSeverity.ERROR,
									"Error while processing component data of "
											+ object.getId());
							componentDetails.clear();
							status = false;
						}
					} catch (TeamRepositoryException e) {
						this.logger.log(MessageSeverity.ERROR,
								"Error while processing component data of "
										+ object.getId() + ": "
										+ e.getMessage());
						componentDetails.clear();
						status = false;
					} finally {
						if (createRepoWS) {
							deleteWorkspace(workspace);
						}
					}
				}
			}
		}
		return status;
	}

	private void prepareComponentHandleFromSnapshot(
			final IScmWorkspace workspace, final IScmObject object) {
		IBaselineSet baselineSet = workspace.getBaselineSet();
		ExecutorService executorService = Executors.newFixedThreadPool(
				SodaConstants.BASELINE_PROCESSING_THREAD_COUNT);

		for (Iterator<IBaselineHandle> itBaseline = baselineSet.getBaselines()
				.iterator(); itBaseline.hasNext();) {
			IBaselineHandle baselineHandle = itBaseline.next();
			Runnable worker = new BaselineProcessor(baselineHandle, object);
			executorService.execute(worker);
		}
		executorService.shutdown();
		while (!executorService.isTerminated()) {
		}
	}

	private IScmWorkspace getWorkspace(final IScmObject object) {
		ScmWorkspace workspace = null;
		IWorkspaceConnection wsConnection = null;
		if (object.getType() == ScmObjectType.STREAM) {
			this.logger.log(MessageSeverity.INFO,
					"Preparing workspace connection for "
							+ ScmObjectType.STREAM.toString() + " "
							+ object.getId());
			wsConnection = getWorkspaceConnection(object.getId());
			if (null != wsConnection) {
				workspace = new ScmWorkspace(wsConnection);
				this.logger.log(MessageSeverity.INFO,
						"Successfully created workspace connection: "
								+ wsConnection.getName());
			} else {
				this.logger.log(MessageSeverity.ERROR,
						"Could not create workspace connection");
			}
		} else if (object.getType() == ScmObjectType.SNAPSHOT) {
			this.logger.log(MessageSeverity.INFO,
					"Preparing workspace connection for "
							+ ScmObjectType.SNAPSHOT.toString() + " "
							+ object.getId());
			wsConnection = getWorkspaceConnection(object.getParentId());
			if (null != wsConnection) {
				this.logger.log(MessageSeverity.INFO,
						"Successfully created workspace connection: "
								+ wsConnection.getName());
				workspace = new ScmWorkspace(wsConnection);
				workspace.setBaselineSet(
						fetchBaselineSet(object.getId(), wsConnection));
			} else {
				this.logger.log(MessageSeverity.ERROR,
						"Could not create workspace connection");
			}
		}
		return workspace;
	}

	private IBaselineSet fetchBaselineSet(final String id,
			final IWorkspaceConnection wsConnection) {
		IBaselineSet baselineSet = null;
		try {
			IItemHandle itemHandle = IComponent.ITEM_TYPE
					.createItemHandle(UUID.valueOf(id), null);
			IBaselineSetHandle baselineSetHandle = null;

			@SuppressWarnings("unchecked")
			List<IBaselineSetHandle> baselineSetHandles = wsConnection
					.getBaselineSets(this.monitor);
			for (IBaselineSetHandle baseline : baselineSetHandles) {
				if (baseline.getItemId().equals(itemHandle.getItemId())) {
					baselineSetHandle = baseline;
					break;
				}
			}
			if (baselineSetHandle == null) {
				baselineSetHandle = (IBaselineSetHandle) this.teamRepository
						.itemManager().fetchCompleteItem(itemHandle,
								IItemManager.DEFAULT, this.monitor);
			}
			baselineSet = (IBaselineSet) this.teamRepository.itemManager()
					.fetchCompleteItem(baselineSetHandle, IItemManager.DEFAULT,
							this.monitor);
		} catch (Exception e) {
			this.logger.log(MessageSeverity.ERROR,
					"Error while processing baseline sets: " + e.getMessage());
		}
		return baselineSet;
	}

	private IWorkspaceConnection getWorkspaceConnection(final String uuid) {

		IWorkspaceConnection workspaceConnection = null;
		try {
			IWorkspaceHandle workspaceHandle = (IWorkspaceHandle) IWorkspace.ITEM_TYPE
					.createItemHandle(UUID.valueOf(uuid), null);
			workspaceConnection = this.workspaceManager
					.getWorkspaceConnection(workspaceHandle, this.monitor);
		} catch (TeamRepositoryException e) {
			if (null != this.logger) {
				this.logger.log(MessageSeverity.ERROR,
						"Failed to create workspace connection: "
								+ e.getMessage());
			}
		}
		return workspaceConnection;
	}

	@Override
	public boolean downloadWithLoadrule(final String streamUUID,
			final String snapshotUUID, final String sandboxPath,
			final String loadRuleComponent, final String loadRuleFie,
			final boolean isLoadRuleFileLocal) {
		boolean status = true;
		ScmObjectType type;
		IScmObject object;
		if (!snapshotUUID.isEmpty()) {
			type = ScmObjectType.SNAPSHOT;
			object = FactorySoDA.createScmObject(type, snapshotUUID,
					streamUUID);
		} else {
			type = ScmObjectType.STREAM;
			object = FactorySoDA.createScmObject(type, streamUUID, "");
		}
		IScmWorkspace scmWS = createRepositoryWorkspace(object,
				SodaConstants.SODA_WORKSPACE_DESCRIPTION);
		IWorkspaceConnection workspaceConnection = null;

		if (null != scmWS) {
			workspaceConnection = scmWS.getWsConnection();
			String tempSandboxLoadRule = sandboxPath + "\\tempSandbox";
			File loadRuleFile = null;
			try {
				loadRuleFile = getLoadRuleFile(loadRuleComponent, loadRuleFie,
						isLoadRuleFileLocal, workspaceConnection,
						tempSandboxLoadRule);
			} catch (TeamRepositoryException e) {
				this.logger.log(MessageSeverity.ERROR,
						"Error while loading load rule file. Stream/Snapshot will not be downloaded : "
								+ e.getMessage());
				status = false;
			}
			if (status && null != loadRuleFile) {
				this.logger.log(MessageSeverity.INFO,
						"Downloading contents from stream/snapshot "
								+ object.getId());
				try (InputStream ins = new FileInputStream(loadRuleFile);
						Reader xmlReader = new InputStreamReader(ins);) {
					ILoadRule2 rule = ILoadRuleFactory.loadRuleFactory
							.getLoadRule(workspaceConnection, xmlReader, null);
					ISharingManager sharingManager = FileSystemCore
							.getSharingManager();
					ISandbox sandbox = sharingManager
							.getSandbox(new PathLocation(sandboxPath), false);
					ILoadOperation loadoperator = rule.getLoadOp(sandbox,
							LoadDilemmaHandler.getDefault(), null);
					loadoperator.run(this.monitor);
					sharingManager.deregister(sandbox, this.monitor);
					FileUtil.deleteDirectory(tempSandboxLoadRule);
					FileUtil.deleteDirectory(sandboxPath + "\\" + ".jazz5");
				} catch (Exception e) {
					this.logger.log(MessageSeverity.ERROR,
							"Failed to load the stream/snapshot using load rule "
									+ e.getMessage());
					status = false;
				}
			} else {
				status = false;
			}
			deleteWorkspace(scmWS);
		} else {
			this.logger.log(MessageSeverity.ERROR,
					"Failed to create workspace connection for "
							+ object.getId());
			status = false;
		}

		return status;
	}

	private IScmWorkspace createRepositoryWorkspace(final IScmObject object,
			final String workspaceDescription) {
		IScmWorkspace scmWS = null;
		if (null != this.teamRepository && this.teamRepository.loggedIn()
				&& null != this.workspaceManager) {
			try {
				StringBuilder sbWSName = new StringBuilder();
				sbWSName.append(SodaConstants.SODA_WORKSPACE_NAME).append("_")
						.append(EnvUtil.getCurrentUser()).append("_");

				String machine = EnvUtil.getCurrentMachine();
				if (!machine.isEmpty()) {
					sbWSName.append(machine).append("_");
				}
				sbWSName.append(DateUtil.getTimeStamp());
				String workspaceName = sbWSName.toString();
				scmWS = getWorkspace(object);
				IBaselineSet baselineSet = null;
				if (null != scmWS) {
					baselineSet = scmWS.getBaselineSet();
					IWorkspaceConnection workspaceConnection = null;
					if (null != baselineSet) {
						workspaceConnection = this.workspaceManager
								.createWorkspace(
										this.teamRepository
												.loggedInContributor(),
										workspaceName, workspaceDescription,
										baselineSet, this.monitor);
					} else {
						workspaceConnection = this.workspaceManager
								.createWorkspace(
										this.teamRepository
												.loggedInContributor(),
										workspaceName, workspaceDescription,
										null, scmWS.getWsConnection(),
										this.monitor);
					}
					scmWS.setWsConnection(workspaceConnection);
				}
			} catch (TeamRepositoryException e) {
				this.logger.log(MessageSeverity.ERROR,
						"Error while creating workspace connection: "
								+ e.getMessage());
			}
		}
		return scmWS;
	}

	private boolean deleteWorkspace(final IScmWorkspace workspace) {
		boolean status = true;
		if (null != this.teamRepository && this.teamRepository.loggedIn()
				&& null != this.workspaceManager) {
			IWorkspaceConnection wsConnection = workspace.getWsConnection();
			if (null != wsConnection) {
				try {
					IWorkspaceHandle wsHandle = (IWorkspaceHandle) wsConnection
							.getContextHandle();
					this.workspaceManager.deleteWorkspace(wsHandle,
							this.monitor);
				} catch (TeamRepositoryException e) {
					this.logger.log(MessageSeverity.ERROR,
							"Error while deleting workspace connection: "
									+ e.getMessage());
					status = false;
				}
			}
		}
		return status;
	}

	private File getLoadRuleFile(final String loadRuleComponent,
			final String loadRuleFile, final boolean isLoadRuleFileLocal,
			final IWorkspaceConnection workspaceConnection,
			final String sandboxPath) throws TeamRepositoryException {
		IComponentHandle componentHandle = null;
		File file = null;
		if (isLoadRuleFileLocal) {
			file = new File(loadRuleFile);
			this.logger.log(MessageSeverity.INFO,
					"load rule file path : " + loadRuleFile);
		} else {
			componentHandle = findLoadRuleComponent(workspaceConnection,
					loadRuleComponent);
			if (null == componentHandle) {
				if (!loadRuleComponent.isEmpty()) {
					this.logger.log(MessageSeverity.ERROR,
							"Error while finding component with name "
									+ loadRuleComponent);
				} else {
					this.logger.log(MessageSeverity.ERROR,
							"Error while finding component ending with name "
									+ SodaConstants.SODA_LOAD_RULE_DOWNLOAD_PROJECT_COMPONENT);
				}
			} else {
				file = findLoadRule(componentHandle, sandboxPath,
						workspaceConnection, loadRuleFile);
			}
		}
		return file;
	}

	private IComponentHandle findLoadRuleComponent(
			final IWorkspaceConnection workspaceConnection,
			final String loadRuleComponent) throws TeamRepositoryException {
		if (!loadRuleComponent.isEmpty()) {
			this.logger.log(MessageSeverity.INFO,
					"Finding component with name " + loadRuleComponent);
		} else {
			this.logger.log(MessageSeverity.INFO,
					"Finding component ending with name "
							+ SodaConstants.SODA_LOAD_RULE_DOWNLOAD_PROJECT_COMPONENT);
		}
		@SuppressWarnings("unchecked")
		List<IComponentHandle> componentHandles = workspaceConnection
				.getComponents();
		int totalComponents = componentHandles.size();
		this.logger.log(MessageSeverity.INFO,
				"Total components: " + totalComponents);
		return componentHandles.parallelStream().filter(c -> {
			try {
				return Objects.equals(loadRuleComponent,
						((IComponent) this.teamRepository.itemManager()
								.fetchCompleteItem(c, IItemManager.DEFAULT,
										this.monitor)).getName());
			} catch (TeamRepositoryException e) {
				return false;
			}
		}).findFirst().orElse(componentHandles.parallelStream().filter(c -> {
			try {
				return ((IComponent) this.teamRepository.itemManager()
						.fetchCompleteItem(c, IItemManager.DEFAULT,
								this.monitor)).getName().endsWith(
										SodaConstants.SODA_LOAD_RULE_DOWNLOAD_PROJECT_COMPONENT);
			} catch (TeamRepositoryException e) {
				return false;
			}
		}).collect(Collectors.collectingAndThen(Collectors.toList(), c -> {
			if (!c.isEmpty()) {
				if (c.size() == 1) {
					return c.get(0);
				} else {
					this.logger.log(MessageSeverity.ERROR,
							"More than one component exist ending with name .project. Please provide the exact component name from which the load rule file can be loaded.");
				}
			}
			return null;
		})));

	}

	private File findLoadRule(final IComponentHandle componentHandle,
			final String sandboxPath,
			final IWorkspaceConnection workspaceConnection,
			final String loadRuleFile) throws TeamRepositoryException {
		File file = null;
		IComponent component = (IComponent) this.teamRepository.itemManager()
				.fetchCompleteItem(componentHandle, IItemManager.DEFAULT,
						this.monitor);
		String componentName = component.getName();
		this.logger.log(MessageSeverity.INFO,
				"Load rule file will be downloaded from component "
						+ componentName);
		this.logger.log(MessageSeverity.INFO, "Finding load rule file...");
		IConfiguration configuration = workspaceConnection
				.configuration(componentHandle);
		IFolderHandle parent = configuration.rootFolderHandle(this.monitor);
		List<IVersionableHandle> rawFilesList = new ArrayList<>();
		getRawFilesList(this.teamRepository, workspaceConnection, component,
				parent, rawFilesList, this.monitor);
		IVersionableHandle versionableHandle = rawFilesList.parallelStream()
				.filter(handle -> {
					try {
						return Objects.equals(loadRuleFile,
								configuration
										.fetchCompleteItem(handle, this.monitor)
										.getName());
					} catch (TeamRepositoryException e) {
						return false;
					}
				}).findFirst()
				.orElse(rawFilesList.parallelStream().filter(v -> {
					try {
						return configuration.fetchCompleteItem(v, this.monitor)
								.getName().endsWith(
										SodaConstants.SODA_LOAD_RULE_DOWNLOAD_LOAD_RULE_FILE_EXTENSION);
					} catch (TeamRepositoryException e) {
						return false;
					}
				}).collect(
						Collectors.collectingAndThen(Collectors.toList(), v -> {
							if (!v.isEmpty()) {
								if (v.size() == 1) {
									return v.get(0);
								} else {
									this.logger.log(MessageSeverity.ERROR,
											"Component " + componentName
													+ " has more than one load rule file. Stream/Snapshot will not be downloaded ");
								}
							}
							return null;
						})));
		if (versionableHandle != null) {
			String fileName = configuration
					.fetchCompleteItem(versionableHandle, this.monitor)
					.getName();
			if (!loadRuleFile.isEmpty()) {
				this.logger.log(MessageSeverity.INFO,
						"Downloading load rule file " + fileName);
				List<File> files = downloadLoadRuleInSandbox(componentHandle,
						sandboxPath, workspaceConnection, versionableHandle);
				Optional<File> findFirst = files.stream().findFirst();
				file = findFirst.isPresent() ? findFirst.get() : null;
			}
		}

		if (null == file) {
			if (!loadRuleFile.isEmpty()) {
				this.logger.log(MessageSeverity.INFO,
						"No load rule file found with name " + loadRuleFile
								+ " in component " + componentName);
			} else {
				this.logger.log(MessageSeverity.INFO,
						"No load rule file found in component "
								+ componentName);
			}
		}
		return file;
	}

	@SuppressWarnings("unchecked")
	private void getRawFilesList(final ITeamRepository repo,
			final IWorkspaceConnection wsConn, final IComponent component,
			final IFolderHandle parent,
			final List<IVersionableHandle> rawFilesList,
			final IProgressMonitor monitor) throws TeamRepositoryException {
		IConfiguration configuration = wsConn
				.configuration((IComponentHandle) component.getItemHandle());
		Map<String, IVersionableHandle> children = null;
		if (parent == null) {
			children = configuration.childEntriesForRoot(null);
		} else {
			children = configuration.childEntries(parent, null);
		}
		if (children != null) {
			for (Entry<String, IVersionableHandle> entry : children
					.entrySet()) {
				IVersionableHandle item = children.get(entry.getKey());
				if (item instanceof IFolderHandle) {
					getRawFilesList(repo, wsConn, component,
							(IFolderHandle) item, rawFilesList, monitor);
				} else {
					if (item instanceof IFileItemHandle) {
						rawFilesList.add(item);
					}
				}
			}
		}
	}

	private List<File> downloadLoadRuleInSandbox(
			final IComponentHandle componentHandle, final String sandboxPath,
			final IWorkspaceConnection workspaceConnection,
			final IVersionableHandle handle) throws TeamRepositoryException {
		IComponent component;
		String componentName = null;
		List<File> files = null;
		List<IVersionableHandle> versionableHandles = new ArrayList<>();
		component = (IComponent) this.teamRepository.itemManager()
				.fetchCompleteItem(componentHandle, IItemManager.DEFAULT,
						this.monitor);
		componentName = component.getName();
		this.logger.log(MessageSeverity.INFO,
				"Downloading load rule file from component " + componentName);
		String sandboxLocation = sandboxPath;
		ILocation location = new PathLocation(sandboxLocation);
		ISharingManager sharingManager = FileSystemCore.getSharingManager();
		ISandbox sandbox = sharingManager.getSandbox(location, false);
		versionableHandles.add(handle);
		LoadDilemmaHandler loadDilemmaHandler = new LoadProblemHandler();
		ILoadOperation loadOperation = IOperationFactory.instance
				.getLoadOperation(loadDilemmaHandler);
		IRelativeLocation relativeLocation = new RelativeLocation(
				componentName);
		loadOperation.requestLoad(sandbox, relativeLocation,
				workspaceConnection, componentHandle, versionableHandles);
		loadOperation.run(this.monitor);
		sharingManager.deregister(sandbox, this.monitor);
		File dir = new File(sandboxPath + "\\" + componentName);
		String[] extensions = new String[]{"loadrule"};
		files = (List<File>) FileUtils.listFiles(dir, extensions, true);
		if (!files.isEmpty()) {
			this.logger.log(MessageSeverity.INFO,
					"Successfully downloaded load rule file to path "
							+ dir.getPath());
		}
		return files;
	}
}