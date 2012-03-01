(function ($)
{
	$.anythingZoomer = 
	{
		defaults: 
		{
			smallArea: "#small",
			largeArea: "#large",
			zoomPort: "#overlay",
			mover: "#mover",
			expansionSize: 30,
			speedMultiplier: 1.5
		}
	}
	$.fn.extend(
	{
		anythingZoomer: function (config)
		{
			var config = $.extend({}, $.anythingZoomer.defaults, config);
			var wrap = $(this);
			var smallArea = $(config.smallArea);
			var largeArea = $(config.largeArea);
			var zoomPort = $(config.zoomPort);
			var mover = $(config.mover);
			var expansionSize = config.expansionSize;
			var speedMultiplier = config.speedMultiplier;
			var over = false;

			function setup(smallArea, largeArea, wrap, zoomPort, mover, expansionSize, speedMultiplier)
			{
				smallArea.show();
				zoomPort.fadeIn();
				mover.css(
				{
					width: mover.data("origWidth"),
					height: mover.data("origHeight"),
					overflow: "hidden",
					position: "absolute"
				})
				wrap.css(
				{
					//width: "auto"
				}).mousemove(function (e)
				{
					var x = e.pageX - smallArea.offset().left;
					var y = e.pageY - smallArea.offset().top;
					if ((x < -expansionSize) || (x > smallArea.width() + expansionSize) || (y < -expansionSize) || (y > smallArea.height() + expansionSize))
					{
						if (over)
						{
							mover.fadeOut(50);
							over = false;
						}
						return;
					}
					else
					{
						if (!over)
						{
							mover.fadeIn();
							over = true;
						}
					}
					mover.css(
					{
						top: y - (mover.height() / 2),
						left: x - (mover.width() / 2)
					});
					largeArea.css(
					{
						left: (-(e.pageX - smallArea.offset().left) * speedMultiplier) + (mover.width() / 2),
						top: (-(e.pageY - smallArea.offset().top) * speedMultiplier) + (mover.height() / 2),
					});
				}).dblclick(function ()
				{
					expand(smallArea, largeArea, wrap, zoomPort, mover, expansionSize, speedMultiplier);
				});
			};

			function expand(smallArea, largeArea, wrap, zoomPort, mover, expansionSize, speedMultiplier)
			{
				smallArea.hide();
				zoomPort.hide();
				mover.fadeIn().data("origWidth", mover.width()).data("origHeight", mover.height()).css(
				{
					position: "static",
					height: "auto",
					width: "auto",
					overflow: "visible"
				});
				wrap.css(
				{
					width: "100%"
				}).unbind().dblclick(function ()
				{
					setup(smallArea, largeArea, wrap, zoomPort, mover, expansionSize, speedMultiplier);
				});
				largeArea.css(
				{
					left: 0,
					top: 0,
					width: largeArea.data("origWidth")
				});
			};
			mover.data("origWidth", mover.width()).data("origHeight", mover.height());
			// Because the largeArea is often hidden, the width() function returns zero, take width from CSS instead  
			largeArea.data("origWidth", largeArea.css("width"));
			setup(smallArea, largeArea, wrap, zoomPort, mover, expansionSize, speedMultiplier);
			return this;
		}
	});
})(jQuery);