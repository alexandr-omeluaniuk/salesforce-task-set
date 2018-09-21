/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.salesforce.ant;

import com.sforce.soap.metadata.CodeCoverageWarning;
import com.sforce.soap.metadata.DebuggingHeader_element;
import com.sforce.soap.metadata.DeployDetails;
import com.sforce.soap.metadata.DeployMessage;
import com.sforce.soap.metadata.DeployResult;
import com.sforce.soap.metadata.DeployStatus;
import com.sforce.soap.metadata.MetadataConnection;
import com.sforce.soap.metadata.RunTestFailure;
import com.sforce.ws.ConnectionException;
import org.apache.tools.ant.BuildException;

/**
 *
 * @author ss
 */
public class CustomDeployTask extends DeployTask {

    @Override
    public void handleResponse(MetadataConnection metadataConnection,
            StatusResult response) throws ConnectionException {
        DebuggingHeader_element debugHeader = new DebuggingHeader_element();
        debugHeader.setDebugLevel(readLogType());
        metadataConnection.__setDebuggingHeader(debugHeader);
        DeployResult result = metadataConnection.checkDeployStatus(response.getId(), true);
        String debug = metadataConnection.getDebuggingInfo() != null
                ? metadataConnection.getDebuggingInfo().getDebugLog() : "";
        if ((debug != null) && (debug.length() > 0)) {
            log("Debugging Information:\n" + debug);
        }
        DeployDetails details = result.getDetails();
        if (details == null) {
            details = new DeployDetails();
            details.setComponentSuccesses(new DeployMessage[0]);
            details.setComponentFailures(new DeployMessage[0]);
        }
        if (!result.isSuccess()) {
            logFailedDeploy(result, details);
        } else if (result.getStatus() == DeployStatus.SucceededPartial) {
            logPartiallySucceededDeploy(details);
        } else {
            logSucceededDeploy(details);
        }
    }
// ========================================== CUSTOMIZATION =======================================

    private void logFailedDeploy(DeployResult result, DeployDetails details) {
        DeployMessage[] errorMessages = details.getComponentFailures();
        StringBuilder failuresLog = new StringBuilder("\n");
        failuresLog.append("*********** DEPLOYMENT FAILED ***********\n");
        failuresLog.append("Request ID: " + result.getId() + "\n");

        if (result.getErrorStatusCode() != null) {
            failuresLog.append("Failure code - " + result.getErrorStatusCode() + ", error message - " + result.getErrorMessage());
            throw new BuildException(failuresLog.toString());
        }

        logComponentFailures(errorMessages, failuresLog);
        logTestFailuresAndCodeCoverage(details, failuresLog);

        if (result.getStatus() == com.sforce.soap.metadata.DeployStatus.Canceled) {
            failuresLog.append("\nRequest canceled!\n");
        }
        failuresLog.append("*********** DEPLOYMENT FAILED ***********\n");

        throw new BuildException(failuresLog.toString());
    }

    private void logPartiallySucceededDeploy(DeployDetails details) {
        DeployMessage[] errorMessages = details.getComponentFailures();
        StringBuilder warningLog = new StringBuilder();

        logComponentFailures(errorMessages, warningLog);
        logTestFailuresAndCodeCoverage(details, warningLog);
        log("*********** DEPLOYMENT PARTIALLY SUCCEEDED ***********\n" + warningLog.toString() + "*********** DEPLOYMENT PARTIALLY SUCCEEDED ***********\n");
    }

    private void logSucceededDeploy(DeployDetails details) {
        StringBuilder warningLog = new StringBuilder();
        int warningIndex = 1;
        for (DeployMessage message : details.getComponentSuccesses()) {
            if (message.getProblemType() == com.sforce.soap.metadata.DeployProblemType.Warning) {
                appendDeployProblemLog(message, warningLog, formatIndexToPrint(warningIndex));
                warningIndex++;
            }
        }
        for (DeployMessage message : details.getComponentFailures()) {
            if (message.getProblemType() == com.sforce.soap.metadata.DeployProblemType.Warning) {
                appendDeployProblemLog(message, warningLog, formatIndexToPrint(warningIndex));
                warningIndex++;
            }
        }

        if (warningIndex > 1) {
            warningLog.insert(0, "All warnings:\n");
        }

        logTestFailuresAndCodeCoverage(details, warningLog);

        if (warningLog.length() > 0) {
            warningLog.append("*********** DEPLOYMENT SUCCEEDED ***********\n");
            warningLog.append("\n");
        }

        log("*********** DEPLOYMENT SUCCEEDED ***********\n" + warningLog.toString());
    }

    private void logComponentFailures(DeployMessage[] errorMessages, StringBuilder failuresLog) {
        StringBuilder deployFailuresLog = new StringBuilder("\nAll Component Failures:\n");
        int deployMessageIndex = 1;
        for (DeployMessage errorMessage : errorMessages) {
            String prefix = formatIndexToPrint(deployMessageIndex);
            appendDeployProblemLog(errorMessage, deployFailuresLog, prefix);
            deployMessageIndex++;
        }
        if (errorMessages.length > 0) {
            failuresLog.append(deployFailuresLog.toString() + "\n");
        }
    }

    private void logTestFailuresAndCodeCoverage(DeployDetails details, StringBuilder failuresLog) {
        com.sforce.soap.metadata.RunTestsResult rtr = details.getRunTestResult();
        RunTestFailure[] testFailures = rtr.getFailures();
        if (testFailures != null) {
            StringBuilder testFailuresLog = new StringBuilder("\nAll Test Failures:\n");
            int testFailureIndex = 1;
            for (RunTestFailure failure : testFailures) {
                String prefix = formatIndexToPrint(testFailureIndex);
                appendFailureLog(failure, testFailuresLog, prefix);
                testFailureIndex++;
            }
            if (testFailures.length > 0) {
                failuresLog.append(testFailuresLog.toString());
            }
        }
        if (rtr.getCodeCoverageWarnings() != null) {
            StringBuilder codeCoverageWarningsLog = new StringBuilder("\nCode Coverage Failures:\n");
            int warningIndex = 1;
            for (CodeCoverageWarning ccw : rtr.getCodeCoverageWarnings()) {
                codeCoverageWarningsLog.append(formatIndexToPrint(warningIndex));
                if (ccw.getName() != null) {
                    String n = (ccw.getNamespace() == null ? "" : new StringBuilder().append(ccw.getNamespace()).append(".").toString()) + ccw.getName();
                    codeCoverageWarningsLog.append("Class: " + n + " -- ");
                }
                codeCoverageWarningsLog.append(ccw.getMessage() + "\n");
                warningIndex++;
            }

            if (warningIndex > 1) {
                failuresLog.append(codeCoverageWarningsLog.toString());
            }
        }
    }

    private String formatIndexToPrint(int index) {
        return index + "." + "  ";
    }

    private void appendFailureLog(RunTestFailure failure, StringBuilder failuresLog, String prefix) {
        failuresLog.append(prefix);

        String name = (failure.getNamespace() == null ? "" : new StringBuilder().append(failure.getNamespace()).append(".").toString()) + failure.getName();
        String spacesToIndent = constructSpacesToIndent(prefix.length());
        String failureMessage = failure.getMessage();
        failureMessage = appendToNewLines(failureMessage, spacesToIndent);

        String stacktrace = failure.getStackTrace();
        stacktrace = appendToNewLines(stacktrace, spacesToIndent);
        failuresLog.append(name + "." + failure.getMethodName() + " -- " + failureMessage + "\n" + spacesToIndent + "Stack trace: " + stacktrace + "\n");
    }

    private void appendDeployProblemLog(DeployMessage deployMessage, StringBuilder log, String prefix) {
        log.append(prefix);
        String fileName = deployMessage.getFileName();
        String fullName = deployMessage.getFullName();

        log.append(fileName);
        if ((fileName != null) && (fullName != null) && (!fileName.contains(fullName))) {
            log.append(" (" + fullName + ")");
        }
        log.append(" -- " + deployMessage.getProblemType() + ": ");
        String problemMessage = deployMessage.getProblem();
        problemMessage = appendToNewLines(problemMessage, constructSpacesToIndent(prefix.length()));
        log.append(problemMessage);

        String loc = " (line " + deployMessage.getLineNumber() + ", column " + deployMessage.getColumnNumber() + ")";

        log.append(loc);
        log.append("\n");
    }

    private String appendToNewLines(String sourceStr, String appendStr) {
        return sourceStr == null ? null : sourceStr.replaceAll("\\n", "\n" + appendStr);
    }

    private String constructSpacesToIndent(int numberOfSpaces) {
        StringBuilder spaceForIndent = new StringBuilder();
        for (int i = 0; i < numberOfSpaces; i++) {
            spaceForIndent.append(" ");
        }
        return spaceForIndent.toString();
    }
}
