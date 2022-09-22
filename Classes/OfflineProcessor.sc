//how to accomodate passing buffers...?
//create buffers when function is called with nil (or server?) as input
//pass buffers into the function at the creation of the synthdef

OfflineProcessor {
	var <sd, <processingGraph;
	var <rtSynth, <isPlaying = false, <isBatchProcessing = false; //RT processing
	var <settingsPath, settingsDict;
	var <numOutChannels; //set when building the synthdef
	var <buffers;
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
		buffers = List();
		processingGraph ? {processingGraph = {}};
	}

	addBuffer {arg newBuf;
		try {
			if(newBuf.bufnum.notNil) {
				buffers.add(newBuf)
			}
		} {
			"Provided object does not respond to .bufnum and will not be added".warn;
		}
	}

	addBuffers {arg bufArr;
		if(bufArr.isKindOf(Collection)) {
			bufArr.do({|buf|
				this.addBuffer(buf)
			})
		}
	}

	processingGraph_ {arg newGraph;
		if(newGraph.isKindOf(Function), {
			processingGraph = newGraph;
		}, {
			// Error(this.class ++ ": processingGraph needs to be a Function").throw;
			(this.class.name ++ ": processingGraph needs to be a Function; new graph not set.").warn;
		});
	}

	// useSoundInForNRT can be used for files >4GB (if the format supports it); in that case sample rate conversion is not available
	buildSD {arg processingGraphArg, numInCh = 1, rt = false, rtFadeTime, silenceInputChannels = true, params, useSoundInForNRT = false; //if inBus is nil, synthdef will be built with PlayBuf ugen; otherwise it will use In.ar(inBus, numInCh) as input
		processingGraphArg !? {processingGraph = processingGraphArg};
		^SynthDef("Processor_" ++ this.hash.abs, {arg buf = 0, in = 0, out = 0, silenceInput = 0;
			var sigIn, sigOut, env;
			if(rt, {
				sigIn = In.ar(in, numInCh);
			}, {
				if(useSoundInForNRT, {
					sigIn = SoundIn.ar((0..(numInCh - 1)));
				}, {
					sigIn = VDiskIn.ar(numInCh, buf, BufRateScale.kr(buf));
				});
			});
			// sig.postln;
			sigOut = processingGraph.value(sigIn, params);
			// "out: ".post; sigOut.postln;
			numOutChannels = sigOut.asArray.size;
			if(rt, {
				//replace output on output channels
				//and optionally on unused inputs as well
				env = Env.asr(1, 1, 1).kr(Done.freeSelf, \gate.kr(1), \fadeTime.kr(rtFadeTime ? 0.2));
				if(silenceInputChannels && (numInCh > numOutChannels), {
					"silencing unused input channels is active".postln;
					sigOut = sigOut.asArray ++ (DC.ar(0) ! (numInCh - numOutChannels));
				});
				sigOut.postln;
				XOut.ar(out, env, sigOut)
			}, {
				Out.ar(0, sigOut);
			});
		});
	}

	//realtime preview
	//we assume that the buffers used are already loaded on the server and we don't deal with them
	play { arg target = Server.default.defaultGroup, addAction = \addAfter, bus = 0, numCh = 1, processingGraphArg, allowProcessingInputChannels = false, silenceInputChannels = false;
		var server;
		var firstInputBus, firstPrivateBus, firstProcessingBus, lastProcessingBus;
		//if we want wiping input, let's add siping single channel synthdef and start it here as needed... for now we just add silent channels to the synthdef
		if(isPlaying, {this.stop});

		if(try{target.server}.notNil, {server = target.server}, {server = Server.default});

		firstInputBus = server.options.numOutputBusChannels;
		firstPrivateBus = server.options.firstPrivateBus;
		firstProcessingBus = bus;
		lastProcessingBus = bus + numCh - 1;
		if(
			(
				((lastProcessingBus >= firstInputBus) && (lastProcessingBus < firstPrivateBus))
				or: {(firstProcessingBus >= firstInputBus) && (firstProcessingBus < firstPrivateBus)}
			)
			and: {allowProcessingInputChannels.not},
			{
				format("%: attempted to process signal from the audio input - aborting to prevent feedback. To override, use set allowProcessingInputChannels argument to true.", this.class.name).warn;
			}, {
				sd = this.buildSD(processingGraphArg, numCh, true, silenceInputChannels: silenceInputChannels);
				isPlaying = true;
				//check for overlapping channels with input

				fork{
					sd.add;
					server.sync;
					if(isPlaying, { //this will prevent from starting if we call stop right after play
						rtSynth = Synth(sd.name, [\in, bus, \out, bus, \silenceInput, silenceInputChannels.asInteger], target, addAction);
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

	processFile {arg pathIn, pathOut, processingGraphArg, tailSize = 0, durOverride, header, format, sampleRate, action, preprocessingFunc, useSoundInForNRT = false; //synth graph is being passed an input array signal and should output the array of channel; durOverride can be a function and will be passed file's duration
		var score, events, options, numInChannels, sd, duration, numFrames;
		var scoreBuffer, server, oscFilePath;
		var maxSynthDefSize = 65516, sdExceedsSize, sdPath;
		var bufnum, usedBufnums;
		var params;
		"pathIn: ".post; pathIn.postln;
		// buffer = CtkBuffer.playbuf(pathIn);
		// buffer = SoundFile(pathIn);
		options = ServerOptions.new;
		server = Server.new;

		// "processingGraphArg: ".post; processingGraphArg.def.sourceCode.postln;

		processingGraphArg !? {this.processingGraph = processingGraphArg};

		// "this.processingGraph: ".post; this.processingGraph.def.sourceCode.postln;
		// "processingGraph: ".post; processingGraph.def.sourceCode.postln;

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
			if(useSoundInForNRT, {
				if(options.sampleRate.asInteger != file.sampleRate.asInteger, {
					Error("Can't convert sample rate when useSoundInForNRT is true").throw
				})
			});
			numFrames = file.numFrames;
			"file's sampleRate: ".post; file.sampleRate.postln;
			"processing sampleRate: ".post; options.sampleRate.postln;
			format = format ? file.sampleFormat;
			header = header ? file.headerFormat;
			"format %, header %".format(format, header).postln;
		});


		score = Score();//(events.asArray);

		preprocessingFunc !? {
			params = preprocessingFunc.(score, options.sampleRate, duration)
		};

		sd = this.buildSD(processingGraph, numInChannels, params: params, useSoundInForNRT: useSoundInForNRT);
		// numOutChannels = this.getNumOutChannels(processingGraph, numInChannels);

		"numOutChannels: ".post; numOutChannels.postln;
		options.numInputBusChannels = numInChannels;
		options.numOutputBusChannels = numOutChannels;
		options.memSize_(2.pow(16));
		options.numWireBufs_(1024);

		if(buffers.size > 0) {
			bufnum = 0;
			usedBufnums = buffers.collect({|buf| buf.bufnum});
			"usedBufnums: ".post; usedBufnums.postln;
			while({
				usedBufnums.includes(bufnum);
			}, {
				bufnum = server.nextBufferNumber(1);
				"trying more bufnums".postln;
			});
		} {
			bufnum = server.nextBufferNumber(1);
		};
		"bufnum: ".post; bufnum.postln;

		// scoreBuffer = Buffer.new(server, numFrames, numInChannels, bufnum); // this was for PlayBuf
		scoreBuffer = Buffer.new(server, 2.pow(16), numInChannels, bufnum); // buffer size for use with VDiskIn

		if(sd.asBytes.size > maxSynthDefSize, {
			sdExceedsSize = true;
			"synthdef too big, writing synthdef file".postln;
			sd.writeDefFile;
			sdPath = SynthDef.synthDefDir +/+ sd.name ++ ".scsyndef";
		}, {
			sdExceedsSize = false;
		});

		events = List();
		if(useSoundInForNRT.not, {
			events.add([0, scoreBuffer.allocMsg().postln, scoreBuffer.cueSoundFileMsg(pathIn).postln]);
			// events.add([0, ]);
		});
		if(buffers.size > 0) {
			buffers.do({|buf|
				if(buf.path.notNil) {
					"adding buffer at ".post; buf.path.postln;
					events.add([0, buf.allocReadMsg(buf.path)]);
				} {
					"adding empty buffer, numFrames ".post; buf.numFrames.postln;
					events.add([0, buf.allocMsg]);
				}
			});
		};
		if(sdExceedsSize.not, {events.add([0, [\d_recv, sd.asBytes]])});
		// [0, [\s_new, sd.name, 1000, 0, 0, \buf, scoreBuffer.bufnum]],
		events.add([0, Synth.basicNew(sd.name, server).newMsg(nil, [\buf, scoreBuffer.bufnum])]);
		events.add([duration, [\c_set, 0, 0]]);

		events.asArray.do({|ev|
			score.add(ev)
		});

		oscFilePath = PathName.tmp ++ sd.name ++ ".osc";
		"writing the score".postln;
		"duration: ".post; duration.postln;
		// score.play;
		// score.write(pathOut, duration, options.sampleRate, header, format, options, action);
		score.recordNRT(oscFilePath, pathOut, useSoundInForNRT.if({pathIn},{nil}), options.sampleRate, header, format, options, "", duration, {
			File.delete(oscFilePath);
			if(sdExceedsSize, {"removing synthdef file at ".post; sdPath.postln; File.delete(sdPath)});
			action.();
			"process completed".postln;
		});

	}

	//pathInPattern uses pathMatch
	//pathOutPattern will have following wildcards:
	// %d - input file source directory
	// %n - input filename without extension
	// %m - input filename with extension
	// %e - input file extension (without full stop)
	// %h - header
	// %i - index into the array of input files' paths
	processAllInDirectory {arg pathInPattern, pathOutPattern, processingGraphArg, tailSize = 0, durOverride, header, format, sampleRate, action, useSoundInForNRT = false;
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
						this.processFile(thisInPath, thisOutPath, processingGraphArg, tailSize, durOverride, header, format, sampleRate, {"also here fired".postln; cond.test = true; cond.signal}, useSoundInForNRT: useSoundInForNRT);
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