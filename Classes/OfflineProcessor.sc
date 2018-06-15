//how to accomodate passing buffers...?
//create buffers when function is called with nil (or server?) as input
//pass buffers into the function at the creation of the synthdef

OfflineProcessor {
	var <sd, <processingGraph;
	var <rtSynth, <isPlaying = false, <isBatchProcessing = false; //RT processing
	var <settingsPath, settingsDict;
	// var <numOutChannels; //updated when building a synthdef
	*new {
		^super.new.init;
	}

	init {
		settingsPath = File.realpath(this.class.filenameSymbol).dirname.withTrailingSlash ++ "../settings/opSettings.dict";
		if(File.exists(settingsPath),
			{
				settingsDict = Object.readArchive(settingsPath)
		}, {
				settingsDict = IdentityDictionary.new
		});
		processingGraph ? {processingGraph = {}};
	}

	getNumOutChannels {arg processingGraph = {}, numInCh = 1;
		var thisNumOutCh;
		SynthDef(nil, { //build synth in case the synth graph contains NamedControls
			var sigIn, sigOut;
			sigIn = In.ar(0, numInCh);
			sigOut = processingGraph.value(sigIn);
			thisNumOutCh = sigOut.asArray.size;
		});
		^thisNumOutCh
	}

	processingGraph_ {arg newGraph;
		if(newGraph.isKindOf(Function), {
			processingGraph = newGraph;
		}, {
			// Error(this.class ++ ": processingGraph needs to be a Function").throw;
			(this.class.name ++ ": processingGraph needs to be a Function, not setting.").warn;
		});
	}

	buildSD {arg processingGraphArg, numInCh = 1, rt = false, rtFadeTime;//, silenceInputChannels = true; //if inBus is nil, synthdef will be built with PlayBuf ugen; otherwise it will use In.ar(inBus, numInCh) as input
		processingGraphArg !? {processingGraph = processingGraphArg};
		^SynthDef("Processor_" ++ this.hash, {arg buf = 0, in = 0, out = 0;
			var sigIn, sigOut, thisNumOutCh, env;
			// sig = SoundIn.ar(numInChannels.collect({|i| i}));
			if(rt, {
				sigIn = In.ar(in, numInCh);
			}, {
				sigIn = PlayBuf.ar(numInCh, buf, BufRateScale.kr(buf));
			});
			// sig.postln;
			sigOut = processingGraph.value(sigIn);
			// "out: ".post; sigOut.postln;
			if(rt, {//replace out on all input channels (and only on them)
				// var inBusesArr, inputSilenceArr, outBusesArr;//, outBusesArr, inBusesNotInOutArr;
				// thisNumOutCh = sigOut.asArray.size;
				// // env = EnvGate.kr(fadeTime: rtFadeTime);
				// inBusesArr = numInCh.collect({|inc| inc + in});
				// outBusesArr = thisNumOutCh.collect({|inc| inc + out});
				// inBusesArr.postln;
				// outBusesArr.postln;
				// inputSilenceArr = inBusesArr.collect({|item, inc| outBusesArr.includes(item).not.asInteger});
				// inputSilenceArr.postln;
				env = Env.asr(1, 1, 1).kr(Done.freeSelf, \gate.kr(1), \fadeTime.kr(rtFadeTime ? 0.2));
				// if(silenceInputChannels, {XOut.ar(in, env * inputSilenceArr, DC.ar(0) ! numInCh)}); //silence input channels - doesn't work as expected...

				// inBusesArr = numInCh.collect({|inc| inc + in});
				// outBusesArr = thisNumOutCh.collect({|inc| inc + out});
				// inBusesNotInOutArr = inBusesArr.select({|item| outBusesArr.includes(item).not});
				// "inBusesNotInOutArr: ".post; inBusesNotInOutArr.postln;
				// env = EnvGate.kr(fadeTime: rtFadeTime);
				//
				// thisNumOutCh.do({|inc|
				// 	if(inBusesArr.includes(outBusesArr[inc]), {
				// 		XOut.ar(out + inc, env, sigOut[inc])
				// 		}, {
				// 			XOut.ar(out + inc, env, sigOut[inc])
				// 	})
				// });
				//
				// if(inBusesNotInOutArr.size > 0, {
				// 	inBusesNotInOutArr.do({|thisOut|
				// 		XOut.ar(out + inc, env, DC.ar(0))
				// 	})
				// });				*/
				XOut.ar(out, env, sigOut)
			}, {
				Out.ar(0, sigOut);
			});
		});
	}

	//realtime preview
	play { arg target = Server.default.defaultGroup, addAction = \addAfter, bus = 0, numCh = 1, processingGraphArg, allowProcessingInputChannels = false;
		var server;
		var firstInputBus, firstPrivateBus, firstProcessingBus, lastProcessingBus;
		//if we want wiping input, let's add siping single channel synthdef and start it here as needed...
		if(isPlaying, {this.stop});

		if(try{target.server}.notNil, {server = target.server}, {server = Server.default});

		firstInputBus = server.options.numOutputBusChannels;
		firstPrivateBus = server.options.firstPrivateBus;
		firstProcessingBus = bus;
		lastProcessingBus = bus + numCh - 1;
		if(
			(
				(
					(lastProcessingBus >= firstInputBus) && (lastProcessingBus < firstPrivateBus)
				)
				|| (
					(firstProcessingBus >= firstInputBus) && (firstProcessingBus < firstPrivateBus)
				)
			)
			&& allowProcessingInputChannels.not,
			{
				format("%: attempted to process signal from the audio input - aborting to prevent feedback. To override, use set allowProcessingInputChannels argument to true.", this.class.name).warn;
			}, {
				sd = this.buildSD(processingGraphArg, numCh, true);
				isPlaying = true;
				//check for overlapping channels with input

				fork{
					sd.add;
					server.sync;
					if(isPlaying, { //this will prevent from starting if we call stop right after play
						rtSynth = Synth(sd.name, [\in, bus, \out, bus], target, addAction);
					});
				}
		});
	}

	stop {
		isBatchProcessing = false;
		isPlaying = false;
		rtSynth.release;
	}

	free {
		this.stop; //anything else?
	}

	processFile {arg pathIn, pathOut, processingGraphArg, tailSize = 0, durOverride, header, format, sampleRate, action; //synth graph is being passed an input array signal and should output the array of channel; durOverride can be a function and will be passed file's duration
		var score, options, numInChannels, numOutChannels, sd, duration, numFrames;
		var scoreBuffer, server, oscFilePath;
		"pathIn: ".post; pathIn.postln;
		// buffer = CtkBuffer.playbuf(pathIn);
		// buffer = SoundFile(pathIn);
		options = ServerOptions.new;
		server = Server.new;

		processingGraphArg !? {processingGraph = processingGraphArg};

		SoundFile.use(pathIn, {|file|
			numInChannels = file.numChannels;
			"numInChannels: ".post; numInChannels.postln;
			"file's duration: ".post; file.duration.postln;
			duration = durOverride !? durOverride.(file.duration) ? file.duration;
			"used duration: ".post; duration.postln;
			duration = duration + tailSize;
			"used duration + tailSize: ".post; duration.postln;
			"processingGraph: ".post; processingGraph.def.sourceCode.postln;
			options.sampleRate = sampleRate ? file.sampleRate;// / 4;
			numFrames = file.numFrames;
			"file's sampleRate: ".post; file.sampleRate.postln;
			"processing sampleRate: ".post; options.sampleRate.postln;
			format = format ? file.sampleFormat;
			header = header ? file.headerFormat;
		});

		sd= this.buildSD(processingGraph, numInChannels);
		numOutChannels = this.getNumOutChannels(processingGraph, numInChannels);

		"numOutChannels: ".post; numOutChannels.postln;
		options.numInputBusChannels = numInChannels;
		options.numOutputBusChannels = numOutChannels;

		scoreBuffer = Buffer.new(server, numFrames, numInChannels);

		score = Score([
			[0, scoreBuffer.allocReadMsg(pathIn).postln],
			[0, [\d_recv, sd.asBytes]],
			// [0, [\s_new, sd.name, 1000, 0, 0, \buf, scoreBuffer.bufnum]],
			[0, Synth.basicNew(sd.name, server).newMsg(nil, [\buf, scoreBuffer.bufnum])],
			[duration, [\c_set, 0, 0]]
		]);

		oscFilePath = PathName.tmp ++ sd.name ++ ".osc";
		// score.play;
		// score.write(pathOut, duration, options.sampleRate, header, format, options, action);
		score.recordNRT(oscFilePath, pathOut, nil, options.sampleRate, header, format, options, "", duration, {File.delete(oscFilePath); "action fired".postln; action.()});

	}

	//pathInPattern uses pathMatch
	//pathOutPattern will have following wildcards:
	// %d - input file source directory
	// %n - input filename without extension
	// %m - input filename with extension
	// %e - input file extension (without full stop)
	// %h - header
	// %i - index into the array of input files' paths
	processAllInDirectory {arg pathInPattern, pathOutPattern, processingGraphArg, tailSize = 0, durOverride, header, format, sampleRate, action;
		var cond, allInputPaths;
		cond = Condition.new(false);
		isBatchProcessing = true;
		allInputPaths = pathInPattern.pathMatch;
		"allInputPaths: ".post; allInputPaths.postln;

		fork{
			allInputPaths.do({|thisInPath, inc|
				// var thisBasename, thisDirectory, thisExtension, thisOutPath,
				var pn, thisOutPath;
				if(isBatchProcessing, {
					// thisPath.postln;
					pn = PathName(thisInPath);
					// thisDirectory = pn.pathOnly;
					// thisBasename = pn.fileNameWithoutExtension;
					// thisBasename.postln;
					// thisOutPath = outPath ++ thisBasename ++ "." ++ header;
					thisOutPath = pathOutPattern.replace("%d", pn.pathOnly).replace("%n", pn.fileNameWithoutExtension).replace("%e", pn.extension).replace("%h", header).replace("%i", inc.asString).replace("%m", pn.fileName);

					"thisOutPath: ".post; thisOutPath.postln;
					inc.postln;

					if(File.exists(PathName(thisOutPath).pathOnly).not, {
						File.mkdir(PathName(thisOutPath).pathOnly)
					});

					if(File.exists(thisOutPath).not, {
						"file doesn't exist".postln;
						"processing file ".post; thisInPath.postln;
						"saving as ".post; thisOutPath.postln;
						this.processFile(thisInPath, thisOutPath, processingGraphArg, tailSize, durOverride, header, format, sampleRate, {"also here fired".postln; cond.test = true; cond.signal});
						cond.test = false;
						"before yield".postln;
						cond.wait;
						"after yield".postln;
					}, {
						thisOutPath.post; ": ".post;
						"file exists".warn;
					});
				});
			});
			isBatchProcessing = false;
		}
	}
}