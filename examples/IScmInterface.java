package com.bosch.cc.as.soda.datacollector.scminterface;

import java.util.List;

import com.bosch.cc.as.soda.datacollector.logger.ILogger;
import com.bosch.cc.as.soda.datacollector.model.DeliveryWorkitemData;
import com.bosch.cc.as.soda.datacollector.model.ScmComponentDetail;

public interface IScmInterface {
	
	public void open();
	
	public boolean login(String url, String user, String password);
	
	public boolean logout();
	
	public void close();

	public void setLogger(ILogger logger);
	
	public boolean findDeliveryWorkitemDetails(String projectArea, String timeline, String iteration, List<DeliveryWorkitemData> deliveryWorkitemDetails);
	
	public boolean findDWDetailsUsingId(String workitemNumber, String timeline,
			String iteration,
			List<DeliveryWorkitemData> deliveryWorkitemDetails);

	public boolean processComponentDetails(String snapshotUUID,
			String streamUUID, boolean createRepoWS,
			List<ScmComponentDetail> componentDetails);
	
	public boolean downloadWithLoadrule(String streamUUID, String snapshotUUID, String sandbox, String loadRuleComponent, String loadRuleFie, boolean isLoadRuleFileLocal);
}
