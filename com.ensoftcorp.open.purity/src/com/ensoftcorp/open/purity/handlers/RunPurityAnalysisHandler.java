package com.ensoftcorp.open.purity.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.ensoftcorp.open.purity.analysis.PurityAnalysis;

/**
 * A menu selection handler for running the purity analysis
 * 
 * @author Ben Holland
 */
public class RunPurityAnalysisHandler extends AbstractHandler {
	public RunPurityAnalysisHandler() {}

	/**
	 * Runs the purity analysis
	 */
	public Object execute(ExecutionEvent event) throws ExecutionException {
		PurityJob job = new PurityJob();
		job.schedule();
		return null;
	}
	
	private static class PurityJob extends Job {
		public PurityJob() {
			super("Running Purity Analysis");
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			PurityAnalysis.run();
			return Status.OK_STATUS;
		}	
	}
	
}
