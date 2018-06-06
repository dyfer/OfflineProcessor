//how to accomodate passing buffers...?
//create buffers when function is called with nil (or server?) as input
//pass buffers into the function at the creation of the synthdef

OfflineProcessor {
	var <sd;
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
	}

	getNumOutChannels {arg synthGraph = {}, numInCh = 1;
		var thisNumOutCh;
		SynthDef(nil, { //build synth in case the synth graph contains NamedControls
			var sigIn, sigOut;
			sigIn = In.ar(0, numInCh);
			sigOut = synthGraph.value(sigIn);
			thisNumOutCh = sigOut.asArray.size;
		});
		^thisNumOutCh
	}

	buildSD {arg synthGraph = {}, numInCh = 1, inBus; //if inBus is nil, synthdef will be built with PlayBuf ugen; otherwise it will use In.ar(inBus, numInCh) as input
		^SynthDef("OfflineProcessorGui_" ++ this.hash, {arg buf = 0;
			var sigIn, sigOut;
			// sig = SoundIn.ar(numInChannels.collect({|i| i}));
			if(inBus.notNil, {
				sigIn = In.ar(inBus, numInCh);
			}, {
				sigIn = PlayBuf.ar(numInCh, buf, BufRateScale.kr(buf));
			});
			// sig.postln;
			sigOut = synthGraph.value(sigIn);
			// "out: ".post; sigOut.postln;
			// thisNumOutCh = sigOut.asArray.size;
			ReplaceOut.ar(0, sigOut);
		});
	}

	processFile {arg pathIn, pathOut, synthGraph = {}, tailSize = 0, header, format, sampleRate, action; //synth graph is being passed an input array signal and should output the array of channel
		var score, options, numInChannels, numOutChannels, sd, duration, numFrames;
		var scoreBuffer, server, oscFilePath;
		"pathIn: ".post; pathIn.postln;
		// buffer = CtkBuffer.playbuf(pathIn);
		// buffer = SoundFile(pathIn);
		options = ServerOptions.new;
		server = Server.new;


		SoundFile.use(pathIn, {|file|
			numInChannels = file.numChannels;
			"numInChannels: ".post; numInChannels.postln;
			duration = file.duration;
			"duration: ".post; duration.postln;
			duration = duration + tailSize;
			"duration + tailSize: ".post; duration.postln;
			"synthGraph: ".post; synthGraph.postln;
			options.sampleRate = sampleRate ? file.sampleRate;// / 4;
			numFrames = file.numFrames;
			"file's sampleRate: ".post; file.sampleRate.postln;
			"processing sampleRate: ".post; options.sampleRate.postln;
			format = format ? file.sampleFormat;
			header = header ? file.headerFormat;
		});

		sd= this.buildSD(synthGraph, numInChannels);
		numOutChannels = this.getNumOutChannels(synthGraph, numInChannels);

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
	processAllInDirectory {arg pathInPattern, pathOutPattern, synthGraph = {}, tailSize = 0, header, format, sampleRate, action;
		var cond, allInputPaths;
		cond = Condition.new(false);
		allInputPaths = pathInPattern.pathMatch;
		"allInputPaths: ".post; allInputPaths.postln;

		fork{
			allInputPaths.do({|thisInPath, inc|
				// var thisBasename, thisDirectory, thisExtension, thisOutPath,
				var pn, thisOutPath;
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
					this.processFile(thisInPath, thisOutPath, synthGraph, tailSize, header, format, sampleRate, {"also here fired".postln; cond.test = true; cond.signal});
					cond.test = false;
					"before yield".postln;
					cond.wait;
					"after yield".postln;
				}, {
					thisOutPath.post; ": ".post;
					"file exists".warn;
				});
			});
		}
	}
}