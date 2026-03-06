# HQL Segments from Data Services

## 1. HrEmployeeDS.java

**Segment 1:**
```hql
select count(hrEmployee.employeeId) from HrEmployee hrEmployee inner join hrEmployee.sysUser su where hrEmployee.joiningDateId.timeDimensionId>:timeDimesionID and hrEmployee.sysTenantId=:tenantID and hrEmployee.hrOrganizationId=:orgId and su.enabled = true
```

**Segment 2:**
```hql
select hrEmployee.employeeId from HrEmployee hrEmployee inner join hrEmployee.sysUser su where hrEmployee.sysTenantId=:tenantID and hrEmployee.hrOrganizationId=:orgId and su.enabled = true and hrEmployee.designationID=:designationID
```

---

## 2. CandidateDS.java

**Segment 1:**
```hql
UPDATE Candidate c SET c.active = :active, c.inReview = :inReview where c.userId = :userID
```

**Segment 2:**
```hql
SELECT candidate.candidateId,candidate.firstName,candidate.middleName,candidate.lastName,candidate.primaryEmail,candidate.mobileNumber,candidate.candidateCode,candidate.dateOfBirth,candidate.expinYears,candidate.expinMonths,industry.industryName,fnareaid.functionalName,reqrole.roleName,candidateAddress.city,candidate.createdDate,candidate.createdByUserId,candidate.sourceType.label, NULLIF(1,1), candidate.duplicate, candidate.reviewDate FROM Candidate candidate left outer join candidate.sysIndustry as industry left outer join candidate.functionalAreaID as fnareaid left outer join candidate.sysRequisitionRole as reqrole left outer join candidate.candidateAddress as candidateAddress WHERE candidate.active=:active and (candidate.duplicate is null or candidate.duplicate = false) and (candidateAddress.addressType = 'Residence' or candidateAddress.addressType = null )
```

---

## 3. InvestmentDS.java

**Segment 1:**
```hql
select investment from HrEmpInvestmentDeclaration investment join FETCH investment.hrEmployee hrEmployee left join FETCH investment.hrEmpInvestmentSections sections left join FETCH sections.prlInvestmentSection prlInvestment left join FETCH prlInvestment.sysInvestmentSection sysSection left join FETCH sysSection.parentSection parent left join FETCH hrEmployee.sysUser sysUser left join FETCH sysUser.hrEmployee Employee where investment.investmentDecID=:investmentDecID and investment.tenantId=:tenantId
```

**Segment 2:**
```hql
select 1 from HrEmpInvestmentSections heis where heis.empInvestmentDeclaration.investmentDecID=:investmentDeclarationId and approverActionType in (:rejectActionstatus) and heis.tenantId=:tenantId and (heis.declaredAmount>0 or heis.actualAmount>0 or heis.approvedAmount>0 or heis.checker1ApprovedAmount>0)
```

---

## 4. WorkflowDS.java

**Segment 1:**
```hql
select SWF.workflowId,SWF.workflowName,SWF.workflowTypeID, SWF.workflowType.workflowType,SWF.organizationID,SWF.tenantID, SWF.statusTypeID,SWF.status.sysContentType.sysType,SWF.workflowType.moduleId, SWF.customWorkFlow,SWF.hrFormID,hrform.formName, SWF.entityID from SysWorkflow SWF left join SWF.hrForm hrform where SWF.organizationID=:organizationId and SWF.tenantID=:tenantId
```

**Segment 2:**
```hql
select SWSR.workflowStageID.workflowStageID,SWSR.sysRoleID,SWSR.roleID.roleName from SysWorkflowStageRole SWSR where SWSR.workflowStageID.workflowStageID in (select SWFS.workflowStageID from SysWorkflowStage SWFS where SWFS.tenantId=:tenantId and SWFS.workflow=:workflowID )
```

---

## 5. HrEmployeeDS.java

**Segment 1:**
```hql
select hrOrganizationId , sysTenantId from HrEmployee where employeeId=:employeeId
```

**Segment 2:**
```hql
SELECT e from HrEmployee e where e.sysUser.userId=:USERID
```

---

## 6. RequisitionDS.java

**Segment 1:**
```hql
update Requisition requisition set requisition.requisitionStatusID=:requisitionStatusID, requisition.modifiedDate=:modifiedDate, requisition.modifiedByUser=:modifiedByUser where requisition.requisitionId=:requisitionID
```

**Segment 2:**
```hql
select requisition from Requisition requisition where requisition.organizationConfig.organizationConfigId=:organizationID and requisition.closingDate>=:sysDate and requisition.active = true order by createdDate desc
```

---

## 7. TmLeaveTypeDS.java

**Segment 1:**
```hql
select t.leaveTypeId,t.leaveTypeCode , t.leaveTypeDescription , t.isConvertibleToEarnedLeave ,t.isCashable,t.label,t.balanceCheck,t.genderId,t.active,t.endday,t.entPercentage from TmLeaveType t inner join t.sysLeaveTypeId sc where t.organizationId=:OrgID and t.tenantId=:TenantID order by t.leaveTypeId desc
```

**Segment 2:**
```hql
select t.sysTypeId,t.sysType from ConfigSysContentType t where t.contentCategory.categoryId=:categoryID
```

---

## 8. HrOrgUnitDS.java

**Segment 1:**
```hql
select hrOrgUnit.unitId,hrOrgUnit.orgUnitHierarchy from HrOrgUnit hrOrgUnit where hrOrgUnit.hrOrganization.organizationId=:organizationId and hrOrgUnit.parentOrgUnit is not null and hrOrgUnit.orgUnitHierarchy is not null and hrOrgUnit.active = true order by hrOrgUnit.orgUnitHierarchy
```

**Segment 2:**
```hql
select hrOrgUnit from HrOrgUnit hrOrgUnit where hrOrgUnit.hrOrganization.organizationId=:organizationId and hrOrgUnit.active= true
```

---

## 9. AttendanceRuleDS.java

**Segment 1:**
```hql
select tm from TmAttendanceRule tm where tm.organizationID=:organizationID and tm.tenantID=:tenantID and tm.altGroupID=:altGroupID
```

**Segment 2:**
```hql
SELECT s.shiftId, s.shiftCode FROM TmShift s JOIN s.shiftTypeId hct JOIN hct.sysContentType sct JOIN sct.contentCategory cat WHERE s.hrOrganization.organizationId = :organizationID AND s.sysTenant.tenantId = :tenantID AND s.activeFlag = :activeFlag AND hct.active = true AND cat.categoryName = 'ShiftType' AND sct.sysType = 'Weekly Off'
```

---

## 10. UserDS.java

**Segment 1:**
```hql
select user.userName , user.userID from OrgUser user where user.organizationID=:organizationID
```

**Segment 2:**
```hql
UPDATE OrgUser u SET u.userName = :userName WHERE u.userID=:userID and u.organizationID=:organizationID
```
